/*
 *    Copyright (c) Sematext International
 *    All Rights Reserved
 *
 *    THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF Sematext International
 *    The copyright notice above does not evidence any
 *    actual or intended publication of such source code.
 */
package com.sematext.in;

import static org.junit.Assert.*;

import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

public class EmailExtractorTest {

  @Test
  public void testMatch() {
    String content = "You may remember myself and Ant from a couple of years back when we\n" +
      "were replacing a legacy Xapian-based search system with Solr. Your\n"+
      "advice was invaluable and the system pretty much runs itself these\n"+
      "days.\n"+
      "However since then Venda was bought by NetSuite (about this time last\n"+
      "year) and we're now tasked with a similar project - this time around\n"+
      "we're replacing a Solr (DSE) system with ElasticSearch.\n"+
      "Once more we're treading in somewhat unfamiliar waters and could do\n"+
      "with some sound professional advice and of course yourself and the\n"+
      "good people at Sematext sprang straight to mind!\n"+
      "With that said would you, or your colleagues, be available to discuss\n"+
      "a few things ElasticSearch related sometime this week? We can get down\n"+
      "to the nitty gritty when we sort out times and so forth but it's\n"+
      "broadly in the same category as our previous discussions -\n"+
      "architecture (both implementation and servers), potential gotchas and\n"+
      "other things that require specialist knowledge.\n"+
      "As for fees and so forth Ant can help out there, as before I'm just\n"+
      "the technical guy and Ant makes things happen.";
    content = content.toLowerCase();
    
    int solrCount = StringUtils.countMatches(content, "solr");
    int esCount = StringUtils.countMatches(content, "elasticsearch");
    
    assertEquals(2, solrCount);
    assertEquals(2, esCount);
  }

}
