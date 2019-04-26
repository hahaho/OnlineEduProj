/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.group7.edu.oss.internal;

import com.group7.edu.oss.ClientException;
import com.group7.edu.oss.HttpMethod;
import com.group7.edu.oss.OSSException;
import com.group7.edu.oss.ServiceException;
import com.group7.edu.oss.common.auth.Credentials;
import com.group7.edu.oss.common.auth.CredentialsProvider;
import com.group7.edu.oss.common.auth.RequestSigner;
import com.group7.edu.oss.common.comm.*;
import com.group7.edu.oss.common.parser.ResponseParseException;
import com.group7.edu.oss.common.parser.ResponseParser;
import com.group7.edu.oss.common.utils.ExceptionFactory;
import com.group7.edu.oss.internal.ResponseParsers.EmptyResponseParser;
import com.group7.edu.oss.model.WebServiceRequest;

import java.net.URI;
import java.util.List;

import static com.group7.edu.oss.common.utils.LogUtils.logException;
import static com.group7.edu.oss.internal.OSSConstants.DEFAULT_CHARSET_NAME;
import static com.group7.edu.oss.internal.OSSUtils.safeCloseResponse;

/**
 * Abstract base class that provides some common functionalities for OSS
 * operations (such as bucket/object/multipart/cors operations).
 */
public abstract class OSSOperation {

    protected volatile URI endpoint;
    protected CredentialsProvider credsProvider;
    protected ServiceClient client;

    protected static OSSErrorResponseHandler errorResponseHandler = new OSSErrorResponseHandler();
    protected static EmptyResponseParser emptyResponseParser = new EmptyResponseParser();
    protected static RetryStrategy noRetryStrategy = new NoRetryStrategy();

    protected OSSOperation(ServiceClient client, CredentialsProvider credsProvider) {
        this.client = client;
        this.credsProvider = credsProvider;
    }

    public URI getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(URI endpoint) {
        this.endpoint = URI.create(endpoint.toString());
    }

    protected ServiceClient getInnerClient() {
        return this.client;
    }

    protected ResponseMessage send(RequestMessage request, ExecutionContext context)
            throws OSSException, ClientException {
        return send(request, context, false);
    }

    protected ResponseMessage send(RequestMessage request, ExecutionContext context, boolean keepResponseOpen)
            throws OSSException, ClientException {
        ResponseMessage response = null;
        try {
            response = client.sendRequest(request, context);
            return response;
        } catch (ServiceException e) {
            assert (e instanceof OSSException);
            throw (OSSException) e;
        } finally {
            if (response != null && !keepResponseOpen) {
                safeCloseResponse(response);
            }
        }
    }

    protected <T> T doOperation(RequestMessage request, ResponseParser<T> parser, String bucketName, String key)
            throws OSSException, ClientException {
        return doOperation(request, parser, bucketName, key, false);
    }

    protected <T> T doOperation(RequestMessage request, ResponseParser<T> parser, String bucketName, String key,
                                boolean keepResponseOpen) throws OSSException, ClientException {
        return doOperation(request, parser, bucketName, key, keepResponseOpen, null, null);
    }

    protected <T> T doOperation(RequestMessage request, ResponseParser<T> parser, String bucketName, String key,
                                boolean keepResponseOpen, List<RequestHandler> requestHandlers, List<ResponseHandler> reponseHandlers)
            throws OSSException, ClientException {

        final WebServiceRequest originalRequest = request.getOriginalRequest();
        request.getHeaders().putAll(client.getClientConfiguration().getDefaultHeaders());
        request.getHeaders().putAll(originalRequest.getHeaders());
        request.getParameters().putAll(originalRequest.getParameters());

        ExecutionContext context = createDefaultContext(request.getMethod(), bucketName, key);

        if (context.getCredentials().useSecurityToken() && !request.isUseUrlSignature()) {
            request.addHeader(OSSHeaders.OSS_SECURITY_TOKEN, context.getCredentials().getSecurityToken());
        }

        context.addRequestHandler(new RequestProgressHanlder());
        if (requestHandlers != null) {
            for (RequestHandler handler : requestHandlers)
                context.addRequestHandler(handler);
        }
        if (client.getClientConfiguration().isCrcCheckEnabled()) {
            context.addRequestHandler(new RequestChecksumHanlder());
        }

        context.addResponseHandler(new ResponseProgressHandler(originalRequest));
        if (reponseHandlers != null) {
            for (ResponseHandler handler : reponseHandlers)
                context.addResponseHandler(handler);
        }
        if (client.getClientConfiguration().isCrcCheckEnabled()) {
            context.addResponseHandler(new ResponseChecksumHandler());
        }

        List<RequestSigner> signerHandlers = this.client.getClientConfiguration().getSignerHandlers();
        if (signerHandlers != null) {
            for (RequestSigner signer : signerHandlers) {
                context.addSignerHandler(signer);
            }
        }

        ResponseMessage response = send(request, context, keepResponseOpen);

        try {
            return parser.parse(response);
        } catch (ResponseParseException rpe) {
            OSSException oe = ExceptionFactory.createInvalidResponseException(response.getRequestId(), rpe.getMessage(),
                    rpe);
            logException("Unable to parse response error: ", rpe);
            throw oe;
        }
    }

    private static RequestSigner createSigner(HttpMethod method, String bucketName, String key, Credentials creds, SignVersion signatureVersion) {
        String resourcePath = "/" + ((bucketName != null) ? bucketName + "/" : "") + ((key != null ? key : ""));

        return new OSSRequestSigner(method.toString(), resourcePath, creds, signatureVersion);
    }

    protected ExecutionContext createDefaultContext(HttpMethod method, String bucketName, String key) {
        ExecutionContext context = new ExecutionContext();
        context.setCharset(DEFAULT_CHARSET_NAME);
        context.setSigner(createSigner(method, bucketName, key, credsProvider.getCredentials(), client.getClientConfiguration().getSignatureVersion()));
        context.addResponseHandler(errorResponseHandler);
        if (method == HttpMethod.POST) {
            context.setRetryStrategy(noRetryStrategy);
        }
        context.setCredentials(credsProvider.getCredentials());
        return context;
    }

    protected ExecutionContext createDefaultContext(HttpMethod method, String bucketName) {
        return this.createDefaultContext(method, bucketName, null);
    }

    protected ExecutionContext createDefaultContext(HttpMethod method) {
        return this.createDefaultContext(method, null, null);
    }

}