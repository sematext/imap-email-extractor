/*
 *    Copyright (c) Sematext International
 *    All Rights Reserved
 *
 *    THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF Sematext International
 *    The copyright notice above does not evidence any
 *    actual or intended publication of such source code.
 */
package com.sematext.in;

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

import javax.mail.Address;
import javax.mail.internet.InternetAddress;

public class EmailExtractor {
  private static final Logger LOG = LoggerFactory.getLogger(EmailExtractor.class);

  public static void main(String[] args) throws ConfigurationException {
    Options options = buildOptions();

    CommandLineParser parser = new DefaultParser();
    try {
      // parse the command line arguments
      CommandLine line = parser.parse(options, args);

      if (line.hasOption("help") || 
          (!line.hasOption("include") && !line.hasOption("enclude")) ) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("EmailExtractor", options);
        return;
      }

      EmailExtractor extractor = new EmailExtractor();
      extractor.extract(line.getOptionValue("include"), line.getOptionValue("exclude"));
    } catch (ParseException exp) {
      System.err.println("Parsing failed.  Reason: " + exp.getMessage());
    }

  }

  private static Options buildOptions() {
    Options options = new Options();

    Option help = Option.builder("h").longOpt("help").desc("Help").required(false).build();
    Option includeFolders = Option.builder("i").longOpt("include").desc("regular expression to include folders")
        .hasArg().required(false).build();
    Option excludeFolders = Option.builder("e").longOpt("exclude").desc("regular expression to exclude folders")
        .hasArg().required(false).build();

    options.addOption(includeFolders).addOption(excludeFolders).addOption(help);
    return options;
  }

  private void extract(String include, String exclude) throws ConfigurationException {
    String[] includes = include == null ? new String[0] : include.split(",");
    String[] excludes = exclude == null ? new String[0] : exclude.split(",");

    CompositeConfiguration config = new CompositeConfiguration();
    config.addConfiguration(new SystemConfiguration());
    config.addConfiguration(new PropertiesConfiguration("config.properties"));

    String[] solrKeywords = config.getStringArray("solr.keywords");
    String[] esKeywords = config.getStringArray("es.keywords");

    IMapFetcher fetcher = new IMapFetcher(config, includes, excludes);
    if (!fetcher.connectToMailBox()) {
      LOG.error("Can't connect to mailbox");
      return;
    }

    StringBuilder sb = new StringBuilder();
    while (fetcher.hasNext()) {
      IMAPMessage mail = fetcher.next();

      try {
        sb.setLength(0);
        sb.append(mail.getSubject());
        sb.append(" ");
        fetcher.getPartContent(mail, sb);

        String content = sb.toString();
        int solrCount = 0;
        int esCount = 0;
        for (String keyword : solrKeywords) {
          solrCount += StringUtils.countMatches(content, keyword);
        }
        for (String keyword : esKeywords) {
          esCount += StringUtils.countMatches(content, keyword);
        }

        if (esCount == 0 && solrCount == 0) {
          continue;
        }

        if (esCount > solrCount) {
          for (Address address : mail.getFrom()) {
            InternetAddress from = (InternetAddress)address;
            System.out.println("from " + from.getAddress() + " ES " + fetcher.getFolder());
          }
          /*for (Address address : mail.getAllRecipients()) {
            InternetAddress to = (InternetAddress)address;
            System.out.println("to " + ((InternetAddress)to).getAddress() + " ES " + fetcher.getFolder());
          }*/
        } else {
          for (Address address : mail.getFrom()) {
            InternetAddress from = (InternetAddress)address;
            System.out.println("from " + from.getAddress() + " Solr " + fetcher.getFolder());
          }
          /*for (Address address : mail.getAllRecipients()) {
            InternetAddress to = (InternetAddress)address;
            System.out.println("to " + ((InternetAddress)to).getAddress() + " Solr " + fetcher.getFolder());
          }*/
        }
      } catch (Exception e) {
        LOG.error("Can't read content from email", e);
      }
    }

    fetcher.disconnectFromMailBox();
  }
}
