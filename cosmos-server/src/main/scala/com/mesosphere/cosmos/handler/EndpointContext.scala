package com.mesosphere.cosmos.handler

import com.mesosphere.cosmos.http.{MediaType, RequestSession}

/** Information extracted from a request that affects endpoint behavior. */
case class EndpointContext[Request, Response](
  requestBody: Request,
  session: RequestSession,
  responseFormatter: Response => Response,
  responseContentType: MediaType
)