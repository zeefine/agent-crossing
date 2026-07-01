package com.agentcrossing.platform.infrastructure.persistence.mybatis.config;

import com.agentcrossing.platform.infrastructure.persistence.mybatis.typehandler.InstantTypeHandler;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.typehandler.JsonNodeTypeHandler;
import com.agentcrossing.platform.infrastructure.persistence.mybatis.typehandler.ObjectJsonTypeHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@Configuration
@MapperScan("com.agentcrossing.platform.infrastructure.persistence.mybatis.mapper")
@ConditionalOnProperty(name = "agent-crossing.storage-mode", havingValue = "mysql")
public class MybatisConfig {
    @Bean(destroyMethod = "close")
    public DataSource mysqlDataSource(
            @Value("${spring.datasource.url:jdbc:mysql://${MYSQL_SERVER:localhost}:${MYSQL_PORT:13306}/${MYSQL_DB:agent_crossing}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&characterEncoding=utf8}") String jdbcUrl,
            @Value("${spring.datasource.username:${MYSQL_USER:}}") String username,
            @Value("${spring.datasource.password:${MYSQL_PASSWORD:}}") String password,
            @Value("${spring.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName(driverClassName);
        config.setPoolName("agent-crossing-mysql");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource mysqlDataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(mysqlDataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/**/*.xml"));
        // ObjectJsonTypeHandler 不能全局注册：它的 baseType 是 Object，会被 MyBatis 当成
        // 任意 Object 参数（包括 @Param("limit") int limit 装箱后的 Integer）的 handler，
        // 导致整数被序列化成 JSON 字符串 "1"，渲染成 SQL 里的 LIMIT '1' 语法错。
        // 它只在 realtime_event.payload 一处用到，mapper XML 里已经显式 typeHandler=... 引用了。
        factoryBean.setTypeHandlers(new InstantTypeHandler(), new JsonNodeTypeHandler());
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(false);
        configuration.setCacheEnabled(false);
        configuration.setJdbcTypeForNull(org.apache.ibatis.type.JdbcType.NULL);
        factoryBean.setConfiguration(configuration);
        return factoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    @Bean
    public DataSourceTransactionManager transactionManager(DataSource mysqlDataSource) {
        return new DataSourceTransactionManager(mysqlDataSource);
    }

    @Bean
    public InitializingBean mysqlSchemaInitializer(DataSource mysqlDataSource) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("schema-mysql.sql"));
        populator.setContinueOnError(false);
        return () -> populator.execute(mysqlDataSource);
    }
}
