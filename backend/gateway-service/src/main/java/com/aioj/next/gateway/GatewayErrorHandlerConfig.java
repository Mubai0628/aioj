package com.aioj.next.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.web.ErrorProperties;
import org.springframework.boot.autoconfigure.web.WebProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webflux.autoconfigure.error.DefaultErrorWebExceptionHandler;
import org.springframework.boot.webflux.error.ErrorAttributes;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;

/** Boot's error handler, wired as Boot wires it, with a log line that leaves the query string out (T2.5). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebProperties.class)
class GatewayErrorHandlerConfig {
    @Bean
    @Order(-1)
    ErrorWebExceptionHandler errorWebExceptionHandler(ErrorAttributes errorAttributes, WebProperties webProperties,
                                                      ObjectProvider<ViewResolver> viewResolvers,
                                                      ServerCodecConfigurer codecs, ApplicationContext context) {
        var handler = new PathOnlyErrorWebExceptionHandler(errorAttributes, webProperties.getResources(),
                webProperties.getError(), context);
        handler.setViewResolvers(viewResolvers.orderedStream().toList());
        handler.setMessageWriters(codecs.getWriters());
        handler.setMessageReaders(codecs.getReaders());
        return handler;
    }

    /**
     * Boot's 500 line quotes the query string and prints the exception with Reactor's checkpoints,
     * which quote it again; a query can carry what the user typed. This one logs every 5xx with the
     * trace id, the path and the exception types down the cause chain.
     */
    static final class PathOnlyErrorWebExceptionHandler extends DefaultErrorWebExceptionHandler {
        private static final Logger log = LoggerFactory.getLogger(PathOnlyErrorWebExceptionHandler.class);
        private static final int MAX_CAUSES = 5;

        PathOnlyErrorWebExceptionHandler(ErrorAttributes errorAttributes, WebProperties.Resources resources,
                                         ErrorProperties errorProperties, ApplicationContext context) {
            super(errorAttributes, resources, errorProperties, context);
        }

        @Override
        protected void logError(ServerRequest request, ServerResponse response, Throwable throwable) {
            if (!response.statusCode().is5xxServerError()) {
                return;
            }
            log.error("{}{} Server Error for HTTP {} \"{}\" error={}", request.exchange().getLogPrefix(),
                    response.statusCode().value(), request.method(), request.path(), describe(throwable));
        }

        private static String describe(Throwable error) {
            StringBuilder out = new StringBuilder();
            Throwable current = error;
            for (int depth = 0; current != null && depth < MAX_CAUSES; depth++) {
                out.append(depth == 0 ? "" : " <- ").append(current.getClass().getSimpleName());
                current = current.getCause() == current ? null : current.getCause();
            }
            return out.toString();
        }
    }
}
