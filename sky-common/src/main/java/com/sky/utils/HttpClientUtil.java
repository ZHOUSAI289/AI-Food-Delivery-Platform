package com.sky.utils;

import com.alibaba.fastjson.JSONObject;
import org.apache.http.NameValuePair;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.apache.http.client.methods.HttpEntityEnclosingRequestBase;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpRequestBase;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Http工具类
 *
 * 【新代码用这四个】doPutJson / doPostRaw / doDelete / doGet(url, paramMap, timeoutMs)
 *   —— 用 try-with-resources，非 200 也会返回响应体（ES 的报错原因都在响应体里）。
 * 【老的三个】doGet / doPost / doPost4Json 保留兼容，但有两个已知问题：
 *   0) 老的 doGet(url, paramMap) 【没有设置任何超时】（HttpClients.createDefault() + 无 setConfig），
 *      而且非 200 只返回空串、异常自己吞掉；新代码请用 doGet(url, paramMap, timeoutMs)。
 *   1) finally 里直接 response.close()，请求没发出去时 response 为 null → NPE 会盖掉真正的异常
 *   2) doPost / doPost4Json 没有关闭 httpClient
 *   新代码别用它们；要发 JSON 用 doPostRaw(url, json, "application/json", timeout)。
 */
public class HttpClientUtil {

    static final  int TIMEOUT_MSEC = 5 * 1000;

    /**
     * 发送GET方式请求
     * @param url
     * @param paramMap
     * @return
     */
    public static String doGet(String url,Map<String,String> paramMap){
        // 创建Httpclient对象
        CloseableHttpClient httpClient = HttpClients.createDefault();

        String result = "";
        CloseableHttpResponse response = null;

        try{
            URIBuilder builder = new URIBuilder(url);
            if(paramMap != null){
                for (String key : paramMap.keySet()) {
                    builder.addParameter(key,paramMap.get(key));
                }
            }
            URI uri = builder.build();

            //创建GET请求
            HttpGet httpGet = new HttpGet(uri);

            //发送请求
            response = httpClient.execute(httpGet);

            //判断响应状态
            if(response.getStatusLine().getStatusCode() == 200){
                result = EntityUtils.toString(response.getEntity(),"UTF-8");
            }
        }catch (Exception e){
            e.printStackTrace();
        }finally {
            try {
                response.close();
                httpClient.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return result;
    }

    /**
     * 发送 GET 请求（可指定超时）—— ES 的 _cat 元数据查询用。
     *
     * 【为什么不改上面那个 doGet】它没有超时（HttpClients.createDefault() + 无 setConfig =
     *   连接/读取都是 JDK 默认的无限等待），非 200 只返回空串，异常也被它自己吞掉。
     *   行为必须一字不动（有历史调用方），所以这里新增重载、复用 send 出口。
     * 【与老 doGet 的区别】connect / 借连接固定 3 秒 → 连不上就快速失败；
     *   socket 用调用方传入的 timeoutMs；非 200 也把响应体返回（ES 的报错原因在里面）。
     * @param url       完整 URL
     * @param paramMap  查询参数，可为 null
     * @param timeoutMs socket 超时（毫秒）
     * @return 响应体（非 200 也有内容）；请求本身失败时抛 IOException
     */
    public static String doGet(String url, Map<String,String> paramMap, int timeoutMs) throws IOException {
        return send("GET", buildUrl(url, paramMap), null, null, timeoutMs);
    }

    /** 把 paramMap 拼到 url 上（null / 空 map 原样返回）。只为新的 doGet 重载服务 */
    private static String buildUrl(String url, Map<String,String> paramMap) throws IOException {
        if (paramMap == null || paramMap.isEmpty()) {
            return url;
        }
        try {
            URIBuilder builder = new URIBuilder(url);
            for (Map.Entry<String, String> param : paramMap.entrySet()) {
                builder.addParameter(param.getKey(), param.getValue());
            }
            return builder.build().toString();
        } catch (URISyntaxException e) {
            throw new IOException("URL 拼接失败：" + url, e);
        }
    }

    /**
     * 发送POST方式请求
     * @param url
     * @param paramMap
     * @return
     * @throws IOException
     */
    public static String doPost(String url, Map<String, String> paramMap) throws IOException {
        // 创建Httpclient对象
        CloseableHttpClient httpClient = HttpClients.createDefault();
        CloseableHttpResponse response = null;
        String resultString = "";

        try {
            // 创建Http Post请求
            HttpPost httpPost = new HttpPost(url);

            // 创建参数列表
            if (paramMap != null) {
                List<NameValuePair> paramList = new ArrayList();
                for (Map.Entry<String, String> param : paramMap.entrySet()) {
                    paramList.add(new BasicNameValuePair(param.getKey(), param.getValue()));
                }
                // 模拟表单
                UrlEncodedFormEntity entity = new UrlEncodedFormEntity(paramList);
                httpPost.setEntity(entity);
            }

            httpPost.setConfig(builderRequestConfig());

            // 执行http请求
            response = httpClient.execute(httpPost);

            resultString = EntityUtils.toString(response.getEntity(), "UTF-8");
        } catch (Exception e) {
            throw e;
        } finally {
            try {
                response.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return resultString;
    }

    /**
     * 发送POST方式请求
     * @param url
     * @param paramMap
     * @return
     * @throws IOException
     */
    public static String doPost4Json(String url, Map<String, String> paramMap) throws IOException {
        // 创建Httpclient对象
        CloseableHttpClient httpClient = HttpClients.createDefault();
        CloseableHttpResponse response = null;
        String resultString = "";

        try {
            // 创建Http Post请求
            HttpPost httpPost = new HttpPost(url);

            if (paramMap != null) {
                //构造json格式数据
                JSONObject jsonObject = new JSONObject();
                for (Map.Entry<String, String> param : paramMap.entrySet()) {
                    jsonObject.put(param.getKey(),param.getValue());
                }
                StringEntity entity = new StringEntity(jsonObject.toString(),"utf-8");
                //设置请求编码
                entity.setContentEncoding("utf-8");
                //设置数据类型
                entity.setContentType("application/json");
                httpPost.setEntity(entity);
            }

            httpPost.setConfig(builderRequestConfig());

            // 执行http请求
            response = httpClient.execute(httpPost);

            resultString = EntityUtils.toString(response.getEntity(), "UTF-8");
        } catch (Exception e) {
            throw e;
        } finally {
            try {
                response.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return resultString;
    }
    private static RequestConfig builderRequestConfig() {
        return RequestConfig.custom()
                .setConnectTimeout(TIMEOUT_MSEC)
                .setConnectionRequestTimeout(TIMEOUT_MSEC)
                .setSocketTimeout(TIMEOUT_MSEC).build();
    }

    /** 发送 PUT 请求（JSON 请求体）—— ES 的建索引 / 写文档都是 PUT */
    public static String doPutJson(String url, String jsonBody) throws IOException {
        return send("PUT", url, jsonBody, "application/json", TIMEOUT_MSEC);
    }

    /**
     * 发送 POST 请求（原始请求体 + 自定义 Content-Type）—— _bulk 用。
     * contentType 传 "application/x-ndjson"；普通 JSON 接口传 "application/json"。
     */
    public static String doPostRaw(String url, String body, String contentType, int timeoutMs) throws IOException {
        return send("POST", url, body, contentType, timeoutMs);
    }

    /** 发送 PUT 请求（JSON 请求体，可指定超时）—— ES 建索引 / 写文档用 */
    public static String doPutJson(String url, String jsonBody, int timeoutMs) throws IOException {
        return send("PUT", url, jsonBody, "application/json", timeoutMs);
    }

    /** 发送 DELETE 请求 —— ES 删索引用 */
    public static String doDelete(String url) throws IOException {
        return send("DELETE", url, null, null, TIMEOUT_MSEC);
    }

    /** 发送 DELETE 请求（可指定超时） */
    public static String doDelete(String url, int timeoutMs) throws IOException {
        return send("DELETE", url, null, null, timeoutMs);
    }

    /**
     * 五种方法共用的出口：建客户端 → 发请求 → 读响应 → 关连接。
     *
     * 【为什么用 try-with-resources，而不是 finally 里 close()】
     * 原来的 doPost 在 finally 里 response.close()：如果请求还没发出去就抛异常（response 还是 null），
     * 那一行会抛 NPE，把真正的异常盖掉。try-with-resources 没这个问题，
     * 而且顺手把 httpClient 也关了（原来的 doPost / doPost4Json 是没有关 httpClient 的）。
     *
     * 【为什么非 200 也把响应体返回】
     * ES 失败时会把原因写在响应体里（"resource_already_exists_exception"、
     * "The bulk request must be terminated by a newline" 之类），这正是排查时要看的东西。
     * 原来的 doGet 遇到非 200 直接返回空串，什么都看不到。
     */
    private static String send(String method, String url, String body, String contentType, int timeoutMs) throws IOException {
        HttpRequestBase request;
        if ("GET".equals(method)) {
            // GET 不带请求体：HttpGet 本身也不是 HttpEntityEnclosingRequestBase
            request = new HttpGet(url);
        } else if ("PUT".equals(method)) {
            request = new HttpPut(url);
        } else if ("DELETE".equals(method)) {
            request = new HttpDelete(url);
        } else {
            request = new HttpPost(url);
        }

        if (body != null && request instanceof HttpEntityEnclosingRequestBase) {
            StringEntity entity = new StringEntity(body, StandardCharsets.UTF_8);
            if (contentType != null) {
                entity.setContentType(contentType);
            }
            ((HttpEntityEnclosingRequestBase) request).setEntity(entity);
        }

        // connect / 借连接固定 3 秒：连不上就该快速失败（与操作类型无关）
        // socket 用调用方给的值：按操作类型给（元数据 10s；_bulk / _search 30s）
        // 注意 socketTimeout 是"两次数据包之间的最大间隔"，不是整个请求的总时长
        request.setConfig(RequestConfig.custom()
                .setConnectTimeout(3000)
                .setConnectionRequestTimeout(3000)
                .setSocketTimeout(timeoutMs)
                .build());

        try (CloseableHttpClient httpClient = HttpClients.createDefault();
             CloseableHttpResponse response = httpClient.execute(request)) {
            return EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
        }
    }
}
