package com.examprep.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "下载凭证")
public class DownloadVO {

    @Schema(description = "文件名")
    private String fileName;

    @Schema(description = "带签名的下载地址（时效内有效）")
    private String url;

    @Schema(description = "有效期（秒）")
    private Integer expiresIn;
}
