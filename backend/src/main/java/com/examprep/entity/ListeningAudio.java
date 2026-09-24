package com.examprep.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("listening_audio")
@Schema(description = "听力音频")
public class ListeningAudio {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;
    private Long fileId;

    private LocalDateTime createdAt;
}
