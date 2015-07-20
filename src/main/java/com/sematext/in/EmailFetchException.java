/*
 *    Copyright (c) Sematext International
 *    All Rights Reserved
 *
 *    THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF Sematext International
 *    The copyright notice above does not evidence any
 *    actual or intended publication of such source code.
 */
package com.sematext.in;

public class EmailFetchException extends Exception {

  private static final long serialVersionUID = 1L;

  public EmailFetchException(String message, Throwable e) {
    super(message, e);
  }
}
