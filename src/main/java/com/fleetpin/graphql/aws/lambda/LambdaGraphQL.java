/*
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.fleetpin.graphql.aws.lambda;

import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2ProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2ProxyResponseEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetpin.graphql.aws.lambda.exceptions.AccessDeniedError;
import com.fleetpin.graphql.builder.SchemaBuilder;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Throwables;

import com.google.common.collect.ImmutableMap;
import graphql.ExecutionResult;
import graphql.ExecutionResultImpl;
import graphql.GraphQL;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPOutputStream;

import static com.google.common.net.HttpHeaders.*;

public abstract class LambdaGraphQL<U, C extends ContextGraphQL> implements RequestHandler<APIGatewayV2ProxyRequestEvent,
        APIGatewayV2ProxyResponseEvent> {
    private static final Logger logger = LoggerFactory.getLogger(LambdaGraphQL.class);
    private final ObjectMapper mapper;
    private final GraphQL build;

    public LambdaGraphQL() throws Exception {
        this.build = buildGraphQL();
        this.mapper = builderObjectMapper();
    }

    @VisibleForTesting
    protected LambdaGraphQL(final GraphQL graphQL) {
        build = graphQL;
        mapper = builderObjectMapper();
    }

    public boolean enableGzipCompression() { return false; }


    public boolean enableAccessLog() {
    	return false;
    }
    /**
     * if on a 500 want the API to show the content
     * @return
     */
    public boolean showFailureCause() {
    	return false;
    }
    @Override
    public APIGatewayV2ProxyResponseEvent handleRequest(
            final APIGatewayV2ProxyRequestEvent input,
            final com.amazonaws.services.lambda.runtime.Context context // Gets confused with ContextGraphQL otherwise
    ) {
        try {
            final var query = mapper.readValue(input.getBody(), GraphQLQuery.class);
            final var user = validate(input.getHeaders().get(AUTHORIZATION)).get();
            if(enableAccessLog()) {
            	logger.info("Executing query {}, for user {}", query.getOperationName(), user);
            }
            final C graphContext = buildContext(user, query);
            final var queryResponse = build.executeAsync(builder -> builder.query(query.getQuery())
                    .operationName(query.getOperationName())
                    .variables(query.getVariables())
                    .context(graphContext));
            graphContext.start(queryResponse);

            final ObjectNode serializedQueryResponse = mapper.valueToTree(queryResponse.get());
            if (serializedQueryResponse.get(Constants.GRAPHQL_ERRORS_FIELD).isEmpty()) {
                serializedQueryResponse.remove(Constants.GRAPHQL_ERRORS_FIELD);
            }

            final var response = new APIGatewayV2ProxyResponseEvent();
            response.setStatusCode(200);

            var responseHeader = Constants.GRAPHQL_RESPONSE_HEADERS;

            var body = serializedQueryResponse.toString();
            if (gzipBody(input.getHeaders())) {
                response.setIsBase64Encoded(true);
                responseHeader = new HashMap(responseHeader);
                responseHeader.put(CONTENT_ENCODING, "gzip");
                body = gzipResult(serializedQueryResponse);
            }

            response.setHeaders(responseHeader);
            response.setBody(body);

            return response;
        } catch (final Exception e) {
            final var error = e.getCause();
            if (error instanceof AccessDeniedError) {
                logger.error("Failed to validate user", e);

                final var result = ExecutionResultImpl.newExecutionResult().addError((AccessDeniedError) error).build();

                final var accessDeniedResponse = new APIGatewayV2ProxyResponseEvent();
                accessDeniedResponse.setStatusCode(200);
                accessDeniedResponse.setHeaders(Constants.GRAPHQL_RESPONSE_HEADERS);
                accessDeniedResponse.setBody(executionResultSpecification(result));

                return accessDeniedResponse;
            } else {
                logger.error("Failed to invoke graph", e);
                final var requestFailedResponse = new APIGatewayV2ProxyResponseEvent();
                requestFailedResponse.setStatusCode(500);
                requestFailedResponse.setHeaders(Constants.GRAPHQL_RESPONSE_HEADERS);
                //don't want to expose internal api 
                if(showFailureCause()) {
                	requestFailedResponse.setBody(Throwables.getStackTraceAsString(e));
                }else {
                	requestFailedResponse.setBody("Internal Server Error");
                }
                return requestFailedResponse;
            }
        } finally {
            LambdaCache.evict();
        }
    }

    private Boolean gzipBody(Map<String, String> headers) {
        if (!enableGzipCompression()) {
            return false;
        }

        var acceptEncodingHeader = headers
                .entrySet()
                .stream()
                .filter(header -> header.getKey().equalsIgnoreCase((ACCEPT_ENCODING)))
                .findAny()
                .orElse(null);

        if (acceptEncodingHeader == null) {
            return false;
        }

        return Arrays
                .stream(acceptEncodingHeader.getValue().trim().split("\\s*,\\s*"))
                .anyMatch(x -> x.equalsIgnoreCase("gzip"));

    }


    private String gzipResult(ObjectNode result) throws JsonProcessingException {
        var data = mapper.writeValueAsBytes(result);
        try(ByteArrayOutputStream bos = new ByteArrayOutputStream();GZIPOutputStream out = new GZIPOutputStream(bos)) {
            out.write(data);
            out.finish();
            return Base64.encodeBase64String(bos.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String executionResultSpecification(final ExecutionResult result) {
        try {
            return mapper.writeValueAsString(result.toSpecification());
        } catch (final JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    protected ObjectMapper builderObjectMapper() {
        return SchemaBuilder.MAPPER;
    }

    protected abstract GraphQL buildGraphQL() throws Exception;

    protected abstract CompletableFuture<U> validate(String authHeader);

    protected abstract C buildContext(U user, GraphQLQuery query);
}
