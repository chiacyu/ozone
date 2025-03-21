/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.helpers;

/**
 * Represent class which has info of error thrown for any operation.
 */
public class ErrorInfo {
  private String code;
  private String message;

  public ErrorInfo(String errorCode, String errorMessage) {
    this.code = errorCode;
    this.message = errorMessage;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String errorCode) {
    this.code = errorCode;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String errorMessage) {
    this.message = errorMessage;
  }

}
