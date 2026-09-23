package com.examprep.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置
 *
 * 分页插件必须注册，否则 Page 查询不会真正分页（会一次性捞出全表）。
 */
@Configuration
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页最大条数，防止前端传 size=99999 拖垮数据库
        pagination.setMaxLimit(200L);
        // 超出最大页数后返回空数据而不是回到首页
        pagination.setOverflow(false);

        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
