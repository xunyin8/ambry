/**
 * Copyright 2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 */
package com.github.ambry.router;

import com.github.ambry.network.RequestInfo;


/**
 * The callback to be used when requests are created and needs to be sent out. The operation manager passes this
 * callback to the associated operation class and the operation uses this callback when requests are created and
 * need to be sent out.
 */
public interface RequestRegistrationCallback<T> {
  public void registerRequestToSend(T routerOperation, RequestInfo request);
}
