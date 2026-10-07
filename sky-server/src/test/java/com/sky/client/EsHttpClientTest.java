package com.sky.client;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sky.entity.ReviewIndexDoc;
import com.sky.properties.EsProperties;
import com.sky.utils.HttpClientUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 客户端层（EsHttpClient）的真机测试 —— 需要一个能连通的 ES。
 *
 * 【为什么默认关掉（@EnabledIfSystemProperty）】
 * 本类必须连到真实 Elasticsearch（本机开发环境是 `192.168.100.129:9200`，见 application-dev.yml）。
 * 别人克隆仓库后跑 `mvn test`、或将来在 CI 上跑，十有八九连不上这台虚拟机 ——
 * 那样这一整个类会以"连接失败"的形态变红，红的是环境而不是代码。
 * 它会掩盖真正的回归：所有人学会"这两条本来就是红的"，之后就没人看测试结果了。
 * 所以默认【不执行】：只有显式打开开关才跑。
 *
 * 【怎么打开】
 * <pre>
 * mvn -o test '-Dtest=EsHttpClientTest,RealEsCheckTest' '-Des.test=true' '-Dsurefire.failIfNoSpecifiedTests=false'
 * </pre>
 * （`-D` 的值都用单引号包住，避免 shell 拆参数；`failIfNoSpecifiedTests=false` 是给
 * "只跑一部分测试"时用的，否则 Maven 会因为没有匹配的测试而失败。）
 *
 * 【为什么不用 @Transactional】
 * ES 不是事务资源，回滚不了。所以这里用【一次性的测试索引名】+ 显式删除来隔离，
 * 绝不碰真实的 review_v1 和 review 别名（线上查询正在用它）。
 *
 * 【这条测试真正要证明的两件事】
 *   1. 建出来的索引【带 IK 分词器】（读 _mapping 断言 ik_max_word）。
 *      少了它，中文检索会静默退化成逐字匹配，而且不报错 —— 极难发现。
 *   2. bulk 写进去的 tags / dishIds / dishNames 是【数组】而不是逗号串。
 *      是字符串的话，keyword 字段就变成一个整串，§3.5 没法按菜名/标签聚合。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "es.test", matches = "true")
class EsHttpClientTest {

    /** 一次性的索引名 —— 绝不能用真实的 review_v1 */
    private static final String TEST_INDEX = "review_vtest";

    @Autowired
    private EsHttpClient esHttpClient;

    @Autowired
    private EsProperties esProperties;

    @AfterEach
    void cleanUp() {
        // 无论成败都清理，别在 ES 里留垃圾（索引不存在时会打一条 warn，无害）
        esHttpClient.deleteIndex(TEST_INDEX);
    }

    @Test
    @DisplayName("mapping 能读到，且里面确实配了 IK")
    void mappingJson_shouldContainIk() {
        String mapping = esHttpClient.mappingJson();

        assertThat(mapping).as("mapping 读不到：检查 resources/es/review-index-mapping.json").isNotNull();
        assertThat(mapping).contains("ik_max_word").contains("ik_smart");
    }

    @Test
    @DisplayName("建索引：索引里真的带上了 ik_max_word（能抓到'没有 body 的 PUT'）")
    void createIndex_shouldApplyIkAnalyzer() {
        assertThat(esHttpClient.createIndex(TEST_INDEX, esHttpClient.mappingJson())).isTrue();

        // 直接读 ES 的 mapping 来证明 —— 这是唯一能区分"用了 IK"和"用了默认分析器"的办法
        // （查询层面区分不出来：默认分析器对中文也是逐字切，单字查询照样命中）
        String mapping = HttpClientUtil.doGet(esProperties.getUri() + "/" + TEST_INDEX + "/_mapping", null);

        assertThat(mapping).contains("ik_max_word");
    }

    @Test
    @DisplayName("bulk：tags / dishIds / dishNames 必须是数组，不是逗号串")
    void bulk_shouldWriteArrays() {
        assertThat(esHttpClient.createIndex(TEST_INDEX, esHttpClient.mappingJson())).isTrue();

        ReviewIndexDoc doc = ReviewIndexDoc.builder()
                .reviewId(9001L)
                .orderId(337L)
                .score(1)
                .sentiment(-1)
                .tags("太咸,份量少")
                .dishIds("53,49")
                .dishNames("蜀味水煮草鱼,米饭")
                .content("有点咸")
                .createTime("2026-10-05 12:00:00")
                .build();

        // I2：bulk 现在返回逐条统计的 BulkResult，不再是 boolean —— 断言"本批一条都没失败"
        assertThat(esHttpClient.bulk(TEST_INDEX, Collections.singletonList(doc)).getFailed())
                .as("这条文档是合法文档，_bulk 不应有失败条目")
                .isZero();

        String stored = HttpClientUtil.doGet(esProperties.getUri() + "/" + TEST_INDEX + "/_doc/9001", null);
        JSONObject source = JSON.parseObject(stored).getJSONObject("_source");

        assertThat(source.get("tags")).as("tags 必须是数组；是字符串说明 toDocJson 没拆").isInstanceOf(JSONArray.class);
        assertThat(source.getJSONArray("tags")).containsExactly("太咸", "份量少");
        assertThat(source.getJSONArray("dishIds")).containsExactly(53, 49);
        assertThat(source.getJSONArray("dishNames")).containsExactly("蜀味水煮草鱼", "米饭");
    }

    @Test
    @DisplayName("索引名解析：不存在的别名返回 null；目标索引按已有版本 +1")
    void indexNameResolution() {
        assertThat(esHttpClient.currentIndex("alias_not_exists_xxx")).isNull();
        assertThat(esHttpClient.resolveTargetIndex(TEST_INDEX)).isEqualTo(TEST_INDEX + "_v1");
    }
}
