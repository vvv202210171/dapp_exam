package aegis.controller;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 从 JSON 请求体里取字符串字段。
 */
final class Jsons {
    /** 工具类，不允许实例化。 */
    private Jsons() {}

    /**
     * 读取字段文本。字段缺失或为 null 时返回空字符串。
     *
     * @param node 请求体
     * @param field 字段名
     * @return 字段文本，没有该字段时为空字符串
     */
    static String text(JsonNode node, String field) {
        if (node == null || node.get(field) == null || node.get(field).isNull()) return "";
        return node.get(field).asText("");
    }
}
