package com.sky.client.impl;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.result.BulkResult;
import com.sky.client.EsHttpClient;
import com.sky.constant.MessageConstant;
import com.sky.exception.ReviewBusinessException;
import com.sky.properties.EsProperties;
import com.sky.entity.ReviewIndexDoc;
import com.sky.utils.HttpClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ES HTTP 客户端实现
 */
@Slf4j
@Component
public class EsHttpClientImpl implements EsHttpClient {

    @Autowired
    private EsProperties esProperties;

    /**
     * 读取ES mapping json文件
     * @return
     */
    public String mappingJson() {
        try {
            ClassPathResource resource = new ClassPathResource("es/review-index-mapping.json");
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("读取ESmapping失败：{}", e.toString());
            return null;
        }
    }

    /**
     * 当前被别名指向的物理索引；别名不存在返回 null。
     *
     * 【为什么 null 只能是"确实不存在"】
     * 下面的 GET 查不出来时 get() 会直接抛 ReviewBusinessException，不会返回 null。
     * 所以这里的 null 只可能来自"ES 回了空数组"这一种情况。这个区分是必须的：
     * 若把"没查出来"也当 null，reindex 会以为没有旧索引 → switchAlias 只发 add →
     * 别名同时指向新旧两个索引（查询返回重复文档、旧索引永不删除）。
     * @param alias
     * @return
     */
    public String currentIndex(String alias) {
        String resp = get("/_cat/aliases/" + alias + "?h=index&format=json");
        JSONArray arr = parseArray(resp, "_cat/aliases/" + alias);
        if(arr.isEmpty()){
            return null;
        }
        return arr.getJSONObject(0).getString("index");
    }

    /**
     * 下一个要用的物理索引名，如 review_v2（首次 review_v1）。
     * 查询失败会抛 ReviewBusinessException（get 内部），不会退回 review_v1 去猜。
     * @param alias
     * @return
     */
    public String resolveTargetIndex(String alias) {
        List<String> indices = listIndices(alias);
        int max = 0;
        // 正则编好放在循环外：没必要每条都编译一次
        Pattern pattern = Pattern.compile(Pattern.quote(alias) + "_v(\\d+)");
        for (String name : indices){
            Matcher m = pattern.matcher(name);
            if (m.matches()){
                max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        return alias + "_v" + (max + 1);
    }

    /**
     * 列出 `{alias}_v*` 的物理索引名。查询失败抛 ReviewBusinessException（与 resolveTargetIndex 同一个约定）。
     * 【清扫调用方必须自己兜住这个异常】见接口注释。
     * @param alias
     * @return
     */
    public List<String> listIndices(String alias) {
        String resp = get("/_cat/indices/" + alias + "_v*?h=index&format=json");
        JSONArray arr = parseArray(resp, "_cat/indices/" + alias + "_v*");
        List<String> names = new ArrayList<>();
        for (Object o : arr){
            names.add(((JSONObject) o).getString("index"));
        }
        return names;
    }

    /**
     * 索引 / 别名下的文档数；索引不存在返回 0（明确的业务事实，不是失败）。
     * 见接口注释：查询失败抛 ReviewBusinessException，绝不返回 0。
     * @param indexOrAlias
     * @return
     */
    public long count(String indexOrAlias) {
        String resp = execute("POST", "/" + indexOrAlias + "/_count", null, null,
                esProperties.getReadTimeoutMs());
        if (resp == null) {
            log.error("统计 {} 文档数失败：请求未成功（网络/超时）", indexOrAlias);
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_QUERY_FAILED);
        }
        JSONObject json = parseObject(resp);
        if (json == null || !json.containsKey("count")) {
            // 索引不存在：ES 回 404 + index_not_found_exception（不存在 ≠ 失败，按 0 处理）
            if (resp.contains("index_not_found_exception")) {
                log.info("索引/别名 {} 不存在，文档数按 0 计", indexOrAlias);
                return 0L;
            }
            log.error("统计 {} 文档数失败：响应无法解析 {}", indexOrAlias, resp);
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_QUERY_FAILED);
        }
        return json.getLongValue("count");
    }

    /**
     * 新建索引
     * @param index
     * @param mappingJson
     * @return
     */
    public boolean createIndex(String index, String mappingJson) {
        if (mappingJson == null || mappingJson.isEmpty()) {
            log.error("建索引 {} 中止：mapping 为空（检查 resources/es/review-index-mapping.json 是否存在）", index);
            return false;
        }
            String resp = execute("PUT","/" + index, mappingJson,
                "application/json", esProperties.getReadTimeoutMs());
        boolean ok = resp != null && resp.contains("\"acknowledged\":true");
        if(!ok){
            log.error("建索引{}失败{}",index,resp);
        }
        return ok;
    }

    /**
     * 删除索引
     * @param index
     */
    public void deleteIndex(String index) {
        if(index == null || index.isEmpty()){
            return;
        }
        String resp = execute("DELETE", "/" + index,null,
                null, esProperties.getReadTimeoutMs());
        if(resp == null || !resp.contains("\"acknowledged\":true")){
            log.error("删索引{}失败(不影响结果：切换成功后的清扫会再试一次，下轮重建也会清理){}",index,resp);
        }
    }

    /**
     * 切换别名
     * @param alias
     * @param newIndex
     * @param oldIndex
     * @return
     */
    public boolean switchAlias(String alias, String newIndex, String oldIndex) {
        JSONArray actions = new JSONArray();
        if(oldIndex != null && !oldIndex.isEmpty()){
            // 只有确认旧索引存在时才 remove —— 否则整个 _aliases 请求会失败
            actions.add(JSON.parseObject("{\"remove\":{\"index\":\"" + oldIndex + "\",\"alias\":\"" + alias + "\"}}"));
        }
        actions.add(JSON.parseObject("{\"add\":{\"index\":\"" + newIndex + "\",\"alias\":\"" + alias + "\"}}"));
        JSONObject body = new JSONObject();
        body.put("actions",actions);
        String resp = execute("POST","/_aliases",body.toJSONString(),
                "application/json", esProperties.getReadTimeoutMs());
        boolean ok = resp != null && resp.contains("\"acknowledged\":true");
        if(!ok){
            log.error("切别名{}失败{}",alias,resp);
        }
        return ok;
    }

    /**
     * 新增/更新文档
     * @param index
     * @param id
     * @param docJson
     * @return
     */
    public boolean indexDoc(String index, String id, String docJson) {
        String resp = execute("PUT", "/" + index + "/_doc/" + id, docJson,
                "application/json", esProperties .getReadTimeoutMs());
        return resp != null
                && (resp.contains("\"result\":\"created\"") || resp.contains("\"result\":\"updated\""));
    }

    /**
     * 批量新增/更新文档；返回逐条统计结果（见 BulkResult 的类注释）。
     * @param index
     * @param docs
     * @return
     */
    public BulkResult bulk(String index, List<ReviewIndexDoc> docs) {
        if(docs == null || docs.isEmpty()){
            // 空批：0 条成功、0 条失败，不是失败
            return BulkResult.builder().indexed(0).failed(0).firstErrors(Collections.emptyList()).build();
        }
        // ① 拼 NDJSON：每条【两行】，两行都要以 \n 结尾，最后一条也要。
        //    漏掉最后一个换行 → "The bulk request must be terminated by a newline"
        StringBuilder sb = new StringBuilder();
        for (ReviewIndexDoc doc : docs){
            sb.append("{\"index\":{\"_index\":\"").append(index)
                    .append("\",\"_id\":\"").append(doc.getReviewId()).append("\"}}\n");
            sb.append(toDocJson(doc)).append("\n");
        }

        // ② Content-Type 必须是 application/x-ndjson（不是 application/json）
        String resp = execute("POST", "/_bulk", sb.toString(),
                "application/x-ndjson", esProperties.getReadTimeoutMs());
        // 【保守判定之一】请求失败：一张都没写进去，按整批失败报，绝不当成"0 条失败"
        if (resp == null) {
            log.error("bulk 请求失败（网络/超时），本批 {} 条全部按失败计", docs.size());
            return wholeBatchFailed(docs.size(), "请求失败（网络/超时），未收到响应");
        }

        // ③ HTTP 200 ≠ 全成功：必须看 items[].index.status（201 新建 / 200 覆盖 / 其他失败）
        JSONObject json = parseObject(resp);
        if (json == null || json.getJSONArray("items") == null) {
            // 【保守判定之二】响应解不出 items：无法证明写成功了，一律按失败
            log.error("bulk 响应无法解析（本批 {} 条全部按失败计）", docs.size());
            return wholeBatchFailed(docs.size(), "响应无法解析（没有 items）");
        }

        int ok = 0, failed = 0;
        List<String> firstErrors = new ArrayList<>();
        for (Object item : json.getJSONArray("items")) {
            JSONObject r = (item instanceof JSONObject) ? ((JSONObject) item).getJSONObject("index") : null;
            // 防御：某条 item 没有 index 键 —— 不能 NPE，也不能当成功
            if (r == null) {
                failed++;
                addFirstError(firstErrors, "item 缺少 index 键：" + item);
                continue;
            }
            int status = r.getIntValue("status");
            if (status == 200 || status == 201) {
                ok++;
            } else {
                failed++;
                // 只留前 3 条（含 _id / status / error.type / error.reason），别让日志爆掉
                addFirstError(firstErrors, errorSummary(r, status));
            }
        }
        log.info("bulk 完成：成功 {} 条 / 失败 {} 条（index={}）", ok, failed, index);
        return BulkResult.builder().indexed(ok).failed(failed).firstErrors(firstErrors).build();
    }

    /** 整批失败：indexed=0、failed=条数、firstErrors 写明原因（保守判定的统一出口） */
    private BulkResult wholeBatchFailed(int size, String reason) {
        return BulkResult.builder()
                .indexed(0)
                .failed(size)
                .firstErrors(Collections.singletonList("本批 " + size + " 条全部按失败计：" + reason))
                .build();
    }

    /** 把一条 item 的错误压成一行：_id / status / error.type / error.reason（截断长度） */
    private String errorSummary(JSONObject item, int status) {
        String id = item.getString("_id");
        JSONObject error = item.getJSONObject("error");
        String type = (error == null) ? null : error.getString("type");
        String reason = (error == null) ? null : error.getString("reason");
        if (reason == null) {
            // 有些失败（例如 mapping 冲突）error 里只有 type/reason 的变体，退回到整条 error 的摘要
            reason = (error == null) ? null : error.toJSONString();
        }
        String summary = "_id=" + id + " status=" + status + " type=" + type + " reason=" + reason;
        return summary.length() > 300 ? summary.substring(0, 300) + "…" : summary;
    }

    private void addFirstError(List<String> firstErrors, String summary) {
        if (firstErrors.size() < 3) {
            firstErrors.add(summary);
            log.error("bulk 某条失败：{}", summary);
        }
    }

    /**
     * 搜索
     * @param indexOrAlias
     * @param queryJson
     * @return
     */
    public String search(String indexOrAlias, String queryJson) {
        return execute("POST", "/" + indexOrAlias +
                "/_search", queryJson, "application/json", esProperties.getReadTimeoutMs());
    }

    /**
     * GET（带超时）—— 只给 currentIndex / resolveTargetIndex 这两个"动手之前必须先知道"的查询用。
     *
     * 【为什么不再用 doGet(url, paramMap)】那个方法没有设置任何超时
     * （HttpClients.createDefault() + 无 setConfig → 连接/读取都是 JDK 默认的无限等待）。
     * ES 所在虚拟机的 IP 是 DHCP 发的，IP 一变 TCP connect 会长时间挂住，把 Tomcat 线程占死。
     * 现在 connect / 借连接固定 3 秒、socket 用 esProperties.readTimeoutMs，连不上就快速失败。
     *
     * 【这里返回 null 只有一个含义：请求失败】网络不通、连不上、超时、异常。
     * 因为底层 send(...) 对非 200 也返回响应体，所以"别名/索引确实不存在"
     * （ES 回 200 + 空数组，或回 404 + index_not_found_exception）会拿到响应体，
     * 由调用方按业务事实判断 —— 两者不再混成同一个 null。
     */
    private String get(String path){
        try {
            return HttpClientUtil.doGet(esProperties.getUri() + path, null, esProperties.getReadTimeoutMs());
        }catch (Exception e){
            log.error("ES 查询失败（本次没有查到，不等于\"不存在\"）：path={} err={}", path, e.toString());
            return null;
        }
    }

    /**
     * 把 _cat 的响应解成数组；查不出来（null / 不是数组）一律抛业务异常。
     * 绝不返回空数组 —— 空数组在调用方是"确实不存在"的意思，两者混起来就是 I1 那个坑。
     */
    private JSONArray parseArray(String resp, String what){
        if (resp == null) {
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_QUERY_FAILED);
        }
        JSONArray arr = null;
        try {
            arr = JSON.parseArray(resp);
        } catch (Exception e) {
            log.error("ES 响应无法解析 what={} err={} resp={}", what, e.toString(), resp);
        }
        if (arr == null) {
            // 拿到了响应但解不出数组（例如 ES 的报错 body）——这不是"不存在"的证据
            log.error("ES 响应不是数组 what={} resp={}", what, resp);
            throw new ReviewBusinessException(MessageConstant.REVIEW_INDEX_QUERY_FAILED);
        }
        return arr;
    }

    private JSONObject parseObject(String resp){
        try {
            return JSON.parseObject(resp);
        } catch (Exception e) {
            return null;
        }
    }

    /** 统一出口：把 IOException 收口成 null —— 业务层不需要 try-catch，只看返回值 */
    private String execute(String method, String path, String body, String contentType, int timeoutMs) {
        String url = esProperties.getUri() + path;
        try {
            if ("PUT".equals(method)) {
                return HttpClientUtil.doPutJson(url, body, timeoutMs);
            }
            if ("DELETE".equals(method)) {
                return HttpClientUtil.doDelete(url, timeoutMs);
            }
            return HttpClientUtil.doPostRaw(url, body, contentType, timeoutMs);
        } catch (Exception e) {
            log.error("ES 请求失败 method={} path={} err={}", method, path, e.toString());
            return null;
        }
    }

    private String toDocJson(ReviewIndexDoc d) {
        JSONObject doc = new JSONObject();
        doc.put("reviewId",  d.getReviewId());
        doc.put("orderId",   d.getOrderId());
        doc.put("score",     d.getScore());
        doc.put("sentiment", d.getSentiment());
        doc.put("content",   d.getContent());
        doc.put("createTime",d.getCreateTime());
        // VO 里这三个字段是【逗号分隔的字符串】（和 group_concat 对齐），
        // 所以这里要拆成数组再放进文档 —— fail 了就说明 VO 的类型又变了
        doc.put("tags",      split(d.getTags()));
        doc.put("dishIds",   splitToLong(d.getDishIds()));
        doc.put("dishNames", split(d.getDishNames()));
        return doc.toJSONString();
    }

    private List<String> split(String s) {
        return (s == null || s.isEmpty()) ? Collections.emptyList() : Arrays.asList(s.split(","));
    }
    private List<Long> splitToLong(String s) {
        List<Long> list = new ArrayList<>();
        for (String x : split(s)) { list.add(Long.valueOf(x)); }
        return list;
    }
}
