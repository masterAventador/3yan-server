package com.sanyan.common.error;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class ErrCodeAutoConfig {

    @Bean
    public List<Class<? extends ErrCode>> errCodeEnums() throws Exception {
        List<Class<? extends ErrCode>> result = new ArrayList<>();
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        CachingMetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        for (var res : resolver.getResources("classpath*:com/sanyan/**/*.class")) {
            MetadataReader reader = factory.getMetadataReader(res);
            String className = reader.getClassMetadata().getClassName();
            Class<?> clazz;
            try { clazz = Class.forName(className); }
            catch (Throwable e) { continue; }
            if (clazz.isEnum() && ErrCode.class.isAssignableFrom(clazz)) {
                @SuppressWarnings("unchecked")
                Class<? extends ErrCode> typed = (Class<? extends ErrCode>) clazz;
                result.add(typed);
            }
        }
        return result;
    }
}
