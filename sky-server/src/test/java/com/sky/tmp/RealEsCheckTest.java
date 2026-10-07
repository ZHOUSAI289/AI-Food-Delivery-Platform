package com.sky.tmp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.properties.EsProperties;
import com.sky.service.AdminReviewService;
import com.sky.utils.HttpClientUtil;
import com.sky.vo.ReviewReindexVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【临时验证用】§3.4 的端到端验证 —— 直接调 service（绕开 JWT 拦截器），
 * 跑完 reindex 后立刻读 ES 的真实状态来断言。
 *
 * 【为什么默认关掉（@EnabledIfSystemProperty）】
 * 本类必须连到真实 Elasticsearch（本机开发环境是 `192.168.100.129:9200`），
 * 没有 ES 的机器 / CI 上跑，只会以"连接失败"的形态变红 —— 红的是环境，不是代码。
 * 所以默认【不执行】，只有显式打开开关才跑。
 *
 * 【⚠️ 它还额外依赖"库里已有那几条评价数据"】
 * 它不是自造数据的测试，而是对【当前这份数据】的快照式验证：
 *   - 断言 ES 文档 `_doc/220`（reviewId=220 那条）存在，且 dishIds / dishNames 是数组、content 已脱敏；
 *   - 断言搜"咸"命中数 ≥ 2。
 * 所以一旦 `review` 表/索引里的数据被清掉（或被换了另一份数据），
 * 即使 ES 连得上，这条测试也照样会红 —— 那是【数据前提不满足】，不是代码回归。
 * 跑之前先确认那份数据还在（`select count(*) from review`，以及 `_doc/220` 查得到）。
 *
 * 【怎么打开】
 * <pre>
 * mvn -o test '-Dtest=EsHttpClientTest,RealEsCheckTest' '-Des.test=true' '-Dsurefire.failIfNoSpecifiedTests=false'
 * </pre>
 * ⚠️ 不要在没有 ES 的环境下加 `-Des.test=true` 去跑：只会得到一个假红。
 *
 * 为什么这么写：
 *  - 走 service 层而不是 curl → 不需要 JWT，不受拦截器干扰
 *  - 断言读的是 ES 的【真实响应】→ 不是"代码自己说自己对"
 *  - 覆盖三件最容易错的事：别名只有一个指向、dishIds/dishNames 是数组、手机号已脱敏
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "es.test", matches = "true")
class RealEsCheckTest {

    @Autowired
    private AdminReviewService adminReviewService;

    @Autowired
    private EsProperties esProperties;

    @Test
    void reindexThenVerifyRealEsState() throws Exception {
        String es = esProperties.getUri();
        String alias = esProperties.getAlias();

        // ① 触发全量重建
        ReviewReindexVO vo = adminReviewService.reindex();
        System.out.println("[TMP] reindex -> indexed=" + vo.getIndexed() + " failed=" + vo.getFailed());
        assertThat(vo.getFailed()).isZero();
        assertThat(vo.getIndexed()).isGreaterThan(0);

        // ② 别名必须【只有一个】指向，且指向物理索引
        String aliases = HttpClientUtil.doGet(es + "/_cat/aliases/" + alias + "?h=index&format=json", null, 5000);
        System.out.println("[TMP] alias json = " + aliases);
        JSONArray arr = JSON.parseArray(aliases);
        assertThat(arr).as("别名应该只指向一个索引").hasSize(1);
        String index = arr.getJSONObject(0).getString("index");
        System.out.println("[TMP] physical index = " + index);

        // ③ 文档里的数组与脱敏（★ 最关键）
        String doc = HttpClientUtil.doGet(es + "/" + index + "/_doc/220", null, 5000);
        System.out.println("[TMP] doc 220 = " + doc);
        JSONObject src = JSON.parseObject(doc).getJSONObject("_source");
        assertThat(src).as("ES 里应该有 reviewId=220 这条").isNotNull();
        assertThat(src.get("dishIds")).as("dishIds 必须是数组，不是逗号串").isInstanceOf(JSONArray.class);
        assertThat(src.get("dishNames")).as("dishNames 必须是数组，不是逗号串").isInstanceOf(JSONArray.class);
        assertThat(src.getString("content"))
                .as("手机号必须在写 ES 前脱敏")
                .contains("***********")
                .doesNotContain("13812345678");
        System.out.println("[TMP] dishIds = " + src.get("dishIds") + " ; dishNames = " + src.get("dishNames"));

        // ④ 中文检索仍命中（IK 在真实文档上生效）
        String search = HttpClientUtil.doGet(es + "/" + alias + "/_search?q=%E5%92%B8", null, 5000);
        int hits = JSON.parseObject(search).getJSONObject("hits").getJSONObject("total").getIntValue("value");
        System.out.println("[TMP] search q=咸 hits = " + hits);
        assertThat(hits).as("搜'咸'应该命中含'有点咸'的评价").isGreaterThanOrEqualTo(2);
    }
}
