/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.accio.main.web;

import io.accio.base.AccioException;
import io.accio.main.web.dto.ErrorMessageDto;
import io.airlift.log.Logger;

import javax.ws.rs.container.AsyncResponse;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.ExceptionMapper;

import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.BiConsumer;

import static io.accio.base.metadata.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.accio.base.metadata.StandardErrorCode.NOT_FOUND;
import static java.lang.String.format;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;

public final class AccioExceptionMapper
        implements ExceptionMapper<Throwable>
{
    private static final Logger LOG = Logger.get(AccioExceptionMapper.class);

    public static BiConsumer<? super Object, ? super Throwable> bindAsyncResponse(AsyncResponse asyncResponse)
    {
        return (response, throwable) -> {
            if (throwable != null) {
                asyncResponse.resume(throwable);
            }
            else if (response instanceof Response) {
                asyncResponse.resume(response);
            }
            else {
                asyncResponse.resume(Response.ok(response).build());
            }
        };
    }

    @Override
    public Response toResponse(Throwable throwable)
    {
        LOG.warn(throwable, format("Exception, type: %s, message: %s", throwable.getClass(), throwable.getMessage()));
        if (throwable instanceof AccioException) {
            return failure((AccioException) throwable);
        }
        else if ((throwable instanceof ExecutionException || throwable instanceof CompletionException)
                && throwable.getCause() instanceof AccioException) {
            return failure((AccioException) throwable.getCause());
        }
        else {
            return Response
                    .status(INTERNAL_SERVER_ERROR)
                    .type(APPLICATION_JSON)
                    .entity(new ErrorMessageDto(GENERIC_INTERNAL_ERROR.name(), throwable.getMessage()))
                    .build();
        }
    }

    private static Response failure(AccioException exception)
    {
        switch (exception.getErrorCode().getType()) {
            case USER_ERROR:
                if (exception.getErrorCode().equals(NOT_FOUND.toErrorCode())) {
                    return Response
                            .status(Response.Status.NOT_FOUND)
                            .type(APPLICATION_JSON)
                            .entity(createErrorMessageDto(exception))
                            .build();
                }
                return Response
                        .status(BAD_REQUEST)
                        .type(APPLICATION_JSON)
                        .entity(createErrorMessageDto(exception))
                        .build();
            case EXTERNAL:
            case INTERNAL_ERROR:
            case INSUFFICIENT_RESOURCES:
            default:
                return Response
                        .status(INTERNAL_SERVER_ERROR)
                        .type(APPLICATION_JSON)
                        .entity(createErrorMessageDto(exception))
                        .build();
        }
    }

    private static ErrorMessageDto createErrorMessageDto(AccioException e)
    {
        return new ErrorMessageDto(e.getErrorCode().getName(), e.getMessage());
    }
}
