package com.examprep;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 大学生多考试备考知识库与题库系统 —— 后端启动类
 *
 * @author 软件工程课程设计
 */
@SpringBootApplication
@MapperScan("com.examprep.**.mapper")
public class ExamPrepApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExamPrepApplication.class, args);
    }
}
