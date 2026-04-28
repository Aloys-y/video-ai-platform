package com.videoai.common.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 初始化上传请求
 *
 * 面试重点：
 * 1. 为什么要先"初始化"再上传分片？
 *    - 服务端预创建会话，记录文件信息
 *    - 计算分片数量，返回给客户端
 *    - 支持秒传检查（根据fileHash判断文件是否已存在）
 *
 * 2. 为什么客户端传fileHash而不是服务端计算？
 *    - 客户端计算hash可以在上传前判断是否需要上传（秒传）
 *    - 减少无效上传，节省带宽
 *
 * 3. 为什么限制fileName长度？
 *    - 防止数据库溢出
 *    - 防止恶意超长文件名
 */
@Data
public class InitUploadRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 文件名
     * 限制：最长255字符，防止数据库溢出
     */
    @NotBlank(message = "文件名不能为空")
    @Size(max = 255, message = "文件名最长255字符")
    private String fileName;

    /**
     * 文件总大小（字节）
     * 限制：最大5GB（5 * 1024 * 1024 * 1024）
     */
    @NotNull(message = "文件大小不能为空")
    @Min(value = 1, message = "文件大小必须大于0")
    @Max(value = 5L * 1024 * 1024 * 1024, message = "文件大小不能超过5GB")
    private Long fileSize;

    /**
     * 文件MD5哈希
     * 用于：
     * 1. 秒传判断
     * 2. 上传完成后校验完整性
     */
    @Pattern(regexp = "^[a-fA-F0-9]{32}$", message = "文件哈希格式错误，应为32位MD5")
    private String fileHash;

    /**
     * 分片大小（字节）
     * 默认5MB，客户端可根据网络状况调整
     * 限制：最小1MB，最大50MB
     */
    @Min(value = 1024 * 1024, message = "分片大小不能小于1MB")
    @Max(value = 50 * 1024 * 1024, message = "分片大小不能超过50MB")
    private Long chunkSize = 5 * 1024 * 1024L;

    /**
     * 文件MIME类型
     * 如：video/mp4, video/quicktime
     * 用于校验文件类型
     */
    private String contentType;

    /**
     * 用户自定义分析提示词
     * 上传时可指定AI分析时的提示语
     */
    @Size(max = 2000, message = "提示词最长2000字符")
    private String prompt;

    /**
     * 自定义元数据
     * JSON格式，用户可以附加任意信息
     */
    @Size(max = 4096, message = "元数据最长4096字符")
    private String metadata;
}
