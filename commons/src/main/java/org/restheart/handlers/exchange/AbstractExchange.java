/*-
 * ========================LICENSE_START=================================
 * restheart-commons
 * %%
 * Copyright (C) 2019 - 2020 SoftInstigate
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.restheart.handlers.exchange;

import io.undertow.server.HttpServerExchange;
import io.undertow.util.AttachmentKey;
import org.slf4j.Logger;

/**
 *
 * @author Andrea Di Cesare <andrea@softinstigate.com>
 * @param <T>
 */
public abstract class AbstractExchange<T> {

    protected static Logger LOGGER;

    protected static final AttachmentKey<Boolean> IN_ERROR_KEY
            = AttachmentKey.create(Boolean.class);

    private static final AttachmentKey<Boolean> RESPONSE_INTERCEPTOR_EXECUTED
            = AttachmentKey.create(Boolean.class);

    public static final int MAX_CONTENT_SIZE = 16 * 1024 * 1024; // 16byte

    public static int MAX_BUFFERS = 1024;
    
    public static void updateBufferSize(int bufferSize) {
        MAX_BUFFERS = 1 + (MAX_CONTENT_SIZE / bufferSize);
    }

    public enum METHOD {
        GET, POST, PUT, DELETE, PATCH, OPTIONS, OTHER
    }

    protected final HttpServerExchange wrapped;

    public AbstractExchange(HttpServerExchange exchange) {
        this.wrapped = exchange;
    }

    /**
     * @return the wrapped HttpServerExchange
     */
    protected HttpServerExchange getWrappedExchange() {
        return wrapped;
    }

    public static boolean isInError(HttpServerExchange exchange) {
        return exchange.getAttachment(IN_ERROR_KEY) != null
                && exchange.getAttachment(IN_ERROR_KEY);
    }

    public static boolean isAuthenticated(HttpServerExchange exchange) {
        return exchange.getSecurityContext() != null
                && exchange.getSecurityContext().getAuthenticatedAccount() != null;
    }

    public static void setInError(HttpServerExchange exchange) {
        exchange
                .putAttachment(IN_ERROR_KEY, true);
    }

    public static boolean responseInterceptorsExecuted(HttpServerExchange exchange) {
        return exchange.getAttachment(RESPONSE_INTERCEPTOR_EXECUTED) != null
                && exchange.getAttachment(RESPONSE_INTERCEPTOR_EXECUTED);
    }

    public static void setResponseInterceptorsExecuted(HttpServerExchange exchange) {
        exchange
                .putAttachment(RESPONSE_INTERCEPTOR_EXECUTED, true);
    }

    public abstract String getContentType();

    /**
     * helper method to check if the request content is Json
     *
     * @return true if Content-Type request header is application/json
     */
    public boolean isContentTypeJson() {
        return "application/json".equals(getContentType())
                || (getContentType() != null
                && getContentType().startsWith("application/json;"));
    }

    /**
     * helper method to check if the request content is Xm
     *
     * @return true if Content-Type request header is application/xml or
     * text/xml
     */
    public boolean isContentTypeXml() {
        return "text/xml".equals(getContentType())
                || (getContentType() != null
                && getContentType().startsWith("text/xml;"))
                || "application/xml".equals(getContentType())
                || (getContentType() != null
                && getContentType().startsWith("application/xml;"));
    }

    /**
     * helper method to check if the request content is text
     *
     * @return true if Content-Type request header starts with text/
     */
    public boolean isContentTypeText() {
        return getContentType() != null
                && getContentType().startsWith("text/");
    }

    /**
     * helper method to check if request is authenticated
     *
     * @return true if request is authenticated
     */
    public boolean isAuthenticated() {
        return getWrappedExchange().getSecurityContext() != null
                && getWrappedExchange().getSecurityContext().getAuthenticatedAccount() != null;
    }

    /**
     * helper method to check if authenticated account is in the specified role
     *
     * @param role
     * @return
     */
    public boolean isAccountInRole(String role) {
        if (!isAuthenticated()) {
            return false;
        } else if (getWrappedExchange().getSecurityContext()
                .getAuthenticatedAccount()
                .getRoles() == null) {
            return false;
        } else {
            return getWrappedExchange().getSecurityContext()
                    .getAuthenticatedAccount()
                    .getRoles().contains(role);
        }
    }
}
