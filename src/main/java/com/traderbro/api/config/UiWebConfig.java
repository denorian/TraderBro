package com.traderbro.api.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built Angular UI from {@code classpath:/static/ui} and falls back to
 * {@code index.html} for Angular client routes (e.g. {@code /ui/dashboard} or
 * {@code /ui/chart/F1}). Assets with a file extension are served directly; the fallback is
 * applied only to paths that resolve to no real resource.
 */
@Configuration
public class UiWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/ui/**")
                .addResourceLocations("classpath:/static/ui/")
                .resourceChain(true)
                .addResolver(new SpaIndexFallbackResolver());
    }

    /** Returns {@code index.html} when the requested SPA path matches no static resource. */
    private static final class SpaIndexFallbackResolver extends PathResourceResolver {

        @Override
        protected Resource getResource(String resourcePath, Resource location) {
            Resource resource = resolve(resourcePath, location);
            if (resource != null) {
                return resource;
            }
            if (resourcePath == null || resourcePath.lastIndexOf('.') >= 0) {
                // No resource and the path looks like a file (has an extension): let it 404.
                return null;
            }
            return resolve("index.html", location);
        }

        private Resource resolve(String path, Resource location) {
            try {
                return super.getResource(path, location);
            } catch (java.io.IOException e) {
                return null;
            }
        }
    }
}