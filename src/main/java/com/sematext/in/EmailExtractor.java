/*
 *    Copyright (c) Sematext International
 *    All Rights Reserved
 *
 *    THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF Sematext International
 *    The copyright notice above does not evidence any
 *    actual or intended publication of such source code.
 */
package com.sematext.in;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sun.mail.imap.IMAPMessage;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

public class EmailExtractor {
  private static final Logger LOG = LoggerFactory.getLogger(EmailExtractor.class);
  private static final SimpleDateFormat FORMAT = new SimpleDateFormat("yyyy-MM-dd");

  public static void main(String[] args) throws ConfigurationException {
    Options options = buildOptions();

    CommandLineParser parser = new DefaultParser();
    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);

      if (line.hasOption("help") || (!line.hasOption("include") && !line.hasOption("exclude"))) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("EmailExtractor", options);
        return;
      }

      EmailExtractor extractor = new EmailExtractor();

      String include = line.getOptionValue("include");
      String exclude = line.getOptionValue("exclude");
      Date fromDate = null;
      if (line.hasOption("from-date")) {
        String fromDateText = line.getOptionValue("from-date");
        try {
          fromDate = FORMAT.parse(fromDateText);
        } catch (java.text.ParseException e) {
          LOG.warn("Invalid from-date value (YYYY-MM-DD) {}", fromDateText);
        }
      }

      extractor.extract(include, exclude, fromDate);
    } catch (ParseException exp) {
      LOG.error("Parsing failed.  Reason: {}", exp.getMessage());
    }

  }

  private static Options buildOptions() {
    Options options = new Options();

    Option help = Option.builder("h").longOpt("help").desc("Help").required(false).build();
    Option includeFolders = Option.builder("i").longOpt("include").desc("regular expression to include folders")
        .hasArg().required(false).build();
    Option excludeFolders = Option.builder("e").longOpt("exclude").desc("regular expression to exclude folders")
        .hasArg().required(false).build();
    Option fromDate = Option.builder("d").longOpt("from-date").desc("process email from date YYYY-MM-DD").hasArg()
        .required(false).build();

    options.addOption(includeFolders).addOption(excludeFolders).addOption(fromDate).addOption(help);

    return options;
  }

  private void extract(String include, String exclude, Date fromDate) throws ConfigurationException {
    String[] includes = include == null ? new String[0] : include.split(",");
    String[] excludes = exclude == null ? new String[0] : exclude.split(",");

    CompositeConfiguration config = new CompositeConfiguration();
    config.addConfiguration(new SystemConfiguration());
    config.addConfiguration(new PropertiesConfiguration("config.properties"));

    List<String> solrKeywords = Arrays.asList(config.getStringArray("solr.keywords"));
    List<String> esKeywords = Arrays.asList(config.getStringArray("es.keywords"));
    List<String> keywords = Lists.newArrayList(Iterables.concat(solrKeywords, esKeywords));

    IMapFetcher fetcher = new IMapFetcher(config, includes, excludes, fromDate);
    fetcher.setFilterKeywords(keywords);
    if (!fetcher.connectToMailBox()) {
      LOG.error("Can't connect to mailbox");
      return;
    }

    int restartCount = 0;
    String lastFolder = "";
    String lastSuccessMsgId = null;

    StringBuilder sb = new StringBuilder();
    while (fetcher.hasNext()) {
      IMAPMessage mail = fetcher.next();
      if (!lastFolder.equals(fetcher.getFolder())) {
        lastFolder = fetcher.getFolder();
        restartCount = 0;
      }

      try {
        sb.setLength(0);
        sb.append(mail.getSubject());
        sb.append(" ");
        fetcher.getPartContent(mail, sb);
        
        Date receivedDate = mail.getReceivedDate();

        String content = sb.toString().toLowerCase();
        int solrCount = 0;
        int esCount = 0;
        for (String keyword : solrKeywords) {
          solrCount += StringUtils.countMatches(content, keyword.toLowerCase());
        }
        for (String keyword : esKeywords) {
          esCount += StringUtils.countMatches(content, keyword.toLowerCase());
        }

        if (esCount == 0 && solrCount == 0) {
          continue;
        }

        if (esCount > solrCount) {
          for (Address address : mail.getFrom()) {
            InternetAddress from = (InternetAddress) address;
            System.out.format("from %s ES %s at %s \n", from.getAddress(), fetcher.getFolder(), FORMAT.format(receivedDate));
          }
          // Extracts the TO, CC, BCC, and NEWSGROUPS recipients.
          for (Address address : mail.getAllRecipients()) {
            InternetAddress to = (InternetAddress) address;
            System.out.format("to %s ES %s at %s \n", to.getAddress(), fetcher.getFolder(), FORMAT.format(receivedDate));
          }
        } else {
          for (Address address : mail.getFrom()) {
            InternetAddress from = (InternetAddress) address;
            System.out.format("from %s Solr %s at %s \n", from.getAddress(), fetcher.getFolder(), FORMAT.format(receivedDate));
          }
          // Extracts the TO, CC, BCC, and NEWSGROUPS recipients.
          for (Address address : mail.getAllRecipients()) {
            InternetAddress to = (InternetAddress) address;
            System.out.format("to %s Solr %s at %s \n", to.getAddress(), fetcher.getFolder(), FORMAT.format(receivedDate));
          }
        }
        lastSuccessMsgId = mail.getMessageID();
      } catch (Exception e) {
        LOG.error("Can't read content from email", e);

        restartCount++;
        // restart (connect/disconnect) and continue from current folder
        if (restartCount <= 3) {
          String curFolder = fetcher.getFolder();
          LOG.info("Restart at folder {} time {}", curFolder, restartCount);
          fetcher.disconnectFromMailBox();
          if (!fetcher.connectToMailBox() || !fetcher.jumpToFolder(curFolder)) {
            LOG.info("Jump to folder {} failed. Skip the failed email and continue", curFolder);
          }
          if (lastSuccessMsgId != null) {
            if (fetcher.jumpToMessageId(lastSuccessMsgId)) {
              LOG.info("Jump to last failed mail");
            } else {
              LOG.info("Can't jump to last failed mail");
            }
          }
        } else {
          LOG.info("Skip the failed email and continue");
        }
      }
    }

    fetcher.disconnectFromMailBox();
  }
}
