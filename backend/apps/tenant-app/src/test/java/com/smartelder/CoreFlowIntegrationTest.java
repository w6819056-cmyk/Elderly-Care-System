package com.smartelder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 一期核心链路集成测试：登录 -> 体征上报 -> 异常告警 -> 家属面板 -> SOS
 * -> 告警处置闭环 -> 家属代下单 -> 支付派单 -> 机构接单打卡 -> 评价 -> 结算与大屏。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CoreFlowIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    private static String elderToken;
    private static String familyToken;
    private static String adminToken;
    private static String orgToken;
    private static Long orderId;
    private static Long workOrderId;
    private static Long vitalAlertId;
    private static Long sosAlertId;

    private String login(String phone) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + phone + "\",\"password\":\"888888\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body).path("data").path("token").asText();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    private JsonNode postJson(String url, String token, String payload) throws Exception {
        var req = post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        if (payload != null) {
            req = req.content(payload);
        }
        String body = mvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    @Test
    @Order(1)
    @DisplayName("四类角色均可登录，账号状态正常")
    void loginAllRoles() throws Exception {
        elderToken = login("13800000001");
        familyToken = login("13800000002");
        adminToken = login("13700000001");
        orgToken = login("13900000001");
        assertThat(elderToken).isNotBlank();
        assertThat(familyToken).isNotBlank();
        assertThat(adminToken).isNotBlank();
        assertThat(orgToken).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("错误密码返回 401，不泄露账号是否存在")
    void loginWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000001\",\"password\":\"bad-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @Order(3)
    @DisplayName("HC-06/HC-09：体征正常写入，超阈值自动触发黄色告警")
    void reportVitalTriggersYellowAlert() throws Exception {
        JsonNode resp = postJson("/api/health/elders/1/vitals", elderToken,
                "{\"type\":\"BLOOD_PRESSURE\",\"value1\":165,\"value2\":98}");
        assertThat(resp.path("data").path("abnormal").asBoolean()).isTrue();
        assertThat(resp.path("data").path("unit").asText()).isEqualTo("mmHg");

        JsonNode alerts = getJson("/api/alerts/elders/1?status=PENDING", familyToken);
        JsonNode matched = null;
        for (JsonNode a : alerts.path("data")) {
            if ("VITAL_ABNORMAL".equals(a.path("type").asText())) {
                matched = a;
            }
        }
        assertThat(matched).isNotNull();
        assertThat(matched.path("level").asText()).isEqualTo("YELLOW");
        vitalAlertId = matched.path("id").asLong();
    }

    @Test
    @Order(4)
    @DisplayName("HC-06：正常体征不产生告警")
    void normalVitalNoAlert() throws Exception {
        long before = getJson("/api/alerts/elders/1?status=PENDING", familyToken).path("data").size();
        JsonNode resp = postJson("/api/health/elders/1/vitals", elderToken,
                "{\"type\":\"HEART_RATE\",\"value1\":72}");
        assertThat(resp.path("data").path("abnormal").asBoolean()).isFalse();
        long after = getJson("/api/alerts/elders/1?status=PENDING", familyToken).path("data").size();
        assertThat(after).isEqualTo(before);
    }

    @Test
    @Order(5)
    @DisplayName("SA-01/SA-04：SOS 触发红色告警，家属与平台多渠道收到消息")
    void sosTriggersRedAlertAndNotifies() throws Exception {
        JsonNode resp = postJson("/api/alerts/sos", elderToken,
                "{\"lng\":116.481,\"lat\":39.921,\"note\":\"感觉胸闷\"}");
        assertThat(resp.path("data").path("level").asText()).isEqualTo("RED");
        sosAlertId = resp.path("data").path("id").asLong();

        JsonNode familyMsgs = getJson("/api/messages", familyToken);
        boolean hasSms = false;
        boolean hasInApp = false;
        for (JsonNode m : familyMsgs.path("data")) {
            if (m.path("bizId").asLong() == sosAlertId && "ALERT".equals(m.path("type").asText())) {
                hasSms |= "SMS".equals(m.path("channel").asText());
                hasInApp |= "IN_APP".equals(m.path("channel").asText());
            }
        }
        assertThat(hasSms).as("红色告警应通过短信渠道触达").isTrue();
        assertThat(hasInApp).as("红色告警应同时投递应用内通知").isTrue();

        JsonNode adminMsgs = getJson("/api/messages", adminToken);
        assertThat(adminMsgs.path("data").size()).isGreaterThan(0);
    }

    @Test
    @Order(6)
    @DisplayName("SA-01：家属不能触发 SOS")
    void familyCannotSos() throws Exception {
        mvc.perform(post("/api/alerts/sos").header("Authorization", "Bearer " + familyToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(7)
    @DisplayName("FM-01/FM-05：家属状态面板汇总体征、告警、在办服务、用药")
    void familySnapshot() throws Exception {
        JsonNode snap = getJson("/api/family/elders/1/snapshot", familyToken).path("data");
        assertThat(snap.path("elderId").asLong()).isEqualTo(1L);
        assertThat(snap.path("name").asText()).isEqualTo("张桂兰");
        assertThat(snap.path("latestVitals").size()).isGreaterThan(0);
        assertThat(snap.path("activeAlerts").size()).isGreaterThan(0);
        assertThat(snap.path("medications").size()).isGreaterThan(0);
    }

    @Test
    @Order(8)
    @DisplayName("越权保护：未绑定家属无法读取他人老人数据")
    void unboundFamilyForbidden() throws Exception {
        String other = login("13800000002");
        mvc.perform(get("/api/family/elders/2/snapshot").header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(9)
    @DisplayName("SA-09：告警处置必须按 待处置->已确认->已回访->已归档 流转")
    void alertHandleLifecycle() throws Exception {
        // 跳过 ACK 直接归档应被拒绝
        mvc.perform(post("/api/alerts/" + sosAlertId + "/handle")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"ARCHIVE\",\"note\":\"直接归档\"}"))
                .andExpect(status().isConflict());

        JsonNode acked = postJson("/api/alerts/" + sosAlertId + "/handle", adminToken,
                "{\"action\":\"ACK\",\"note\":\"已电话联系老人\"}");
        assertThat(acked.path("data").path("status").asText()).isEqualTo("ACKED");

        JsonNode followed = postJson("/api/alerts/" + sosAlertId + "/handle", adminToken,
                "{\"action\":\"FOLLOW\",\"note\":\"社区卫生站已上门\"}");
        assertThat(followed.path("data").path("status").asText()).isEqualTo("FOLLOWED");

        JsonNode archived = postJson("/api/alerts/" + sosAlertId + "/handle", adminToken,
                "{\"action\":\"ARCHIVE\",\"note\":\"处置完成\"}");
        assertThat(archived.path("data").path("status").asText()).isEqualTo("ARCHIVED");

        JsonNode logs = getJson("/api/alerts/" + sosAlertId + "/logs", adminToken).path("data");
        // TRIGGER + ACKED + FOLLOWED + ARCHIVED
        assertThat(logs.size()).isEqualTo(4);
    }

    @Test
    @Order(10)
    @DisplayName("SV-01/SV-10：家属代老人下单，订单归属老人")
    void familyPlacesOrder() throws Exception {
        JsonNode items = getJson("/api/public/services?category=HOUSEKEEPING", familyToken).path("data");
        long itemId = items.get(0).path("id").asLong();
        JsonNode order = postJson("/api/orders?elderId=1", familyToken,
                "{\"itemId\":" + itemId + ",\"address\":\"北京市朝阳区幸福里 3 号楼 502\",\"remark\":\"请下午来\"}");
        assertThat(order.path("data").path("elderId").asLong()).isEqualTo(1L);
        assertThat(order.path("data").path("status").asText()).isEqualTo("PENDING_PAY");
        orderId = order.path("data").path("id").asLong();
    }

    @Test
    @Order(11)
    @DisplayName("SV-10：预约时间早于当前时间被拒绝")
    void pastAppointTimeRejected() throws Exception {
        mvc.perform(post("/api/orders?elderId=1").header("Authorization", "Bearer " + familyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\":1,\"appointTime\":\"2020-01-01 09:00:00\",\"address\":\"测试地址\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(12)
    @DisplayName("SV-11：支付后按区域与负载自动派单并生成工单")
    void payTriggersAutoDispatch() throws Exception {
        JsonNode paid = postJson("/api/orders/" + orderId + "/pay", familyToken, null);
        assertThat(paid.path("data").path("status").asText()).isEqualTo("PENDING_ACCEPT");
        assertThat(paid.path("data").path("providerId").asLong()).isEqualTo(1L);
        assertThat(paid.path("data").path("workOrderStatus").asText()).isEqualTo("DISPATCHED");

        JsonNode wo = getJson("/api/provider/orders/" + orderId + "/work-order", orgToken).path("data");
        workOrderId = wo.path("workOrder").path("id").asLong();
        assertThat(workOrderId).isNotNull();
    }

    @Test
    @Order(13)
    @DisplayName("越权保护：其他机构不能操作不属于自己的工单")
    void otherOrgCannotOperate() throws Exception {
        String otherOrg = login("13900000002");
        mvc.perform(post("/api/provider/work-orders/" + workOrderId + "/accept")
                        .header("Authorization", "Bearer " + otherOrg)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(14)
    @DisplayName("SV-05/SV-06/SV-13：接单->开始打卡->结束打卡，订单进入待评价并生成结算")
    void providerAcceptAndCheckin() throws Exception {
        JsonNode accepted = postJson("/api/provider/work-orders/" + workOrderId + "/accept", orgToken,
                "{\"nurseId\":6}");
        assertThat(accepted.path("data").path("status").asText()).isEqualTo("IN_SERVICE");
        assertThat(accepted.path("data").path("workOrderStatus").asText()).isEqualTo("ACCEPTED");

        JsonNode started = postJson(
                "/api/provider/work-orders/" + workOrderId + "/checkin?action=START", orgToken,
                "{\"lng\":116.481,\"lat\":39.921}");
        assertThat(started.path("data").path("workOrderStatus").asText()).isEqualTo("IN_SERVICE");

        JsonNode ended = postJson(
                "/api/provider/work-orders/" + workOrderId + "/checkin?action=END", orgToken,
                "{\"lng\":116.481,\"lat\":39.921,\"note\":\"完成保洁并协助整理药盒\"}");
        assertThat(ended.path("data").path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(ended.path("data").path("workOrderStatus").asText()).isEqualTo("COMPLETED");
    }

    @Test
    @Order(15)
    @DisplayName("SV-14：重复评价被拒绝，评价后订单完成")
    void reviewOnce() throws Exception {
        JsonNode reviewed = postJson("/api/orders/" + orderId + "/review", elderToken,
                "{\"score\":5,\"content\":\"服务很细致\"}");
        assertThat(reviewed.path("data").path("status").asText()).isEqualTo("COMPLETED");

        mvc.perform(post("/api/orders/" + orderId + "/review").header("Authorization", "Bearer " + elderToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"score\":4,\"content\":\"再评一次\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(16)
    @DisplayName("SV-12：已完成的订单不可取消")
    void cannotCancelCompleted() throws Exception {
        mvc.perform(post("/api/orders/" + orderId + "/cancel").header("Authorization", "Bearer " + familyToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(17)
    @DisplayName("OP-02/OP-08：服务商审核通过后自动开通机构管理员账号")
    void auditProviderOpensAccount() throws Exception {
        JsonNode audited = postJson("/api/admin/providers/3/audit", adminToken,
                "{\"decision\":\"APPROVE\",\"note\":\"资质核验通过\"}");
        assertThat(audited.path("data").path("auditStatus").asText()).isEqualTo("APPROVED");

        String newOrgAdmin = login("13900000003");
        assertThat(newOrgAdmin).isNotBlank();

        mvc.perform(post("/api/admin/providers/3/audit").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(18)
    @DisplayName("OP-03/OP-09：项目审核通过后才出现在商城")
    void itemVisibleOnlyAfterApproval() throws Exception {
        long before = getJson("/api/public/services", familyToken).path("data").size();
        JsonNode audited = postJson("/api/admin/items/4/audit", adminToken,
                "{\"decision\":\"APPROVE\"}");
        assertThat(audited.path("data").path("auditStatus").asText()).isEqualTo("APPROVED");
        long after = getJson("/api/public/services", familyToken).path("data").size();
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    @Order(19)
    @DisplayName("OP-06/OP-12：数据大屏指标可用且口径一致")
    void dashboardStats() throws Exception {
        JsonNode stats = getJson("/api/admin/dashboard", adminToken).path("data");
        assertThat(stats.path("elderCount").asLong()).isGreaterThanOrEqualTo(2);
        assertThat(stats.path("orderCount").asLong()).isGreaterThanOrEqualTo(3);
        assertThat(stats.path("redAlertCount").asLong()).isGreaterThanOrEqualTo(0);
        assertThat(stats.path("totalRevenue").asDouble()).isGreaterThan(0);
    }

    @Test
    @Order(20)
    @DisplayName("USR-02/USR-03：家属绑定需老人确认后才生效")
    void bindingNeedsElderConfirmation() throws Exception {
        String elder2Token = login("13800000003");

        JsonNode binding = postJson("/api/family/bindings", familyToken,
                "{\"elderId\":2,\"relation\":\"姐姐\"}");
        long bindingId = binding.path("data").path("id").asLong();
        assertThat(binding.path("data").path("status").asText()).isEqualTo("PENDING");

        // 未确认前家属无法读取该老人数据
        mvc.perform(get("/api/family/elders/2/snapshot").header("Authorization", "Bearer " + familyToken))
                .andExpect(status().isForbidden());

        // 非老人本人不能确认绑定
        mvc.perform(post("/api/family/bindings/" + bindingId + "/decide?accept=true")
                        .header("Authorization", "Bearer " + familyToken))
                .andExpect(status().isForbidden());

        // 老人本人确认后生效
        mvc.perform(post("/api/family/bindings/" + bindingId + "/decide?accept=true")
                        .header("Authorization", "Bearer " + elder2Token))
                .andExpect(status().isOk());

        JsonNode snap = getJson("/api/family/elders/2/snapshot", familyToken).path("data");
        assertThat(snap.path("elderId").asLong()).isEqualTo(2L);
    }

    @Test
    @Order(21)
    @DisplayName("OP-01：停用账号后该账号无法登录")
    void disableUserBlocksLogin() throws Exception {
        postJson("/api/admin/users/7/status", adminToken, "{\"status\":\"DISABLED\"}");
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800000003\",\"password\":\"888888\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(22)
    @DisplayName("鉴权：无 token 访问受保护接口返回 401")
    void anonymousRejected() throws Exception {
        mvc.perform(get("/api/my/orders")).andExpect(status().isUnauthorized());
    }
}
