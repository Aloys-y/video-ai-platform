package com.videoai.common.dto.response;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeCardPreviewResponse {

    /** 原始文件名 */
    private String fileName;

    /** 建议 cardCode（已检查DB唯一性，但仍可修改） */
    private String cardCode;

    /** 提取/默认的标题，可修改 */
    private String title;

    /** 默认分类，可修改 */
    private String category;

    /** 主题编码 */
    private String subjectCode;

    /** 别名列表 */
    private List<String> aliases;

    /** 标签列表 */
    private List<String> tags;

    /** Markdown 全文，可修改 */
    private String contentMarkdown;

    /** 是否启用 */
    private Boolean enabled;

    /** 是否 timeless */
    private Boolean timeless;
}
