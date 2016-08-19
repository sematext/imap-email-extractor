/*
 *    Copyright (c) Sematext International
 *    All Rights Reserved
 *
 *    THIS IS UNPUBLISHED PROPRIETARY SOURCE CODE OF Sematext International
 *    The copyright notice above does not evidence any
 *    actual or intended publication of such source code.
 */
package com.sematext.in;

import com.google.common.collect.Lists;
import com.sun.mail.imap.IMAPMessage;

import org.apache.commons.configuration.CompositeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.mail.FetchProfile;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.search.AndTerm;
import javax.mail.search.BodyTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.DateTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

public class IMapFetcher implements Iterator<IMAPMessage> {
  private static final Logger LOG = LoggerFactory.getLogger(IMapFetcher.class);
  
  private final CompositeConfiguration config;
  private final String[] excludes;
  private final String[] includes;

  private Store mailbox;
  private FolderIterator folderIter;
  private MessageIterator msgIter;
  private static FetchProfile fp = new FetchProfile();

  private int batchSize = 200;
  private int fetchSize = 32 * 1024;
  private int cTimeout = 30 * 1000;
  private int rTimeout = 60 * 1000;

  private List<String> keywords;

  private Date fetchMailsSince;

  class FolderIterator implements Iterator<Folder> {
    private Store mailbox;
    private List<Folder> folders = null;
    private Folder lastFolder = null;

    public FolderIterator(Store mailBox) throws EmailFetchException {
      this.mailbox = mailBox;
      this.folders = Lists.newArrayList();
      getTopLevelFolders(mailBox);
    }

    public boolean hasNext() {
      return !folders.isEmpty();
    }

    public Folder next() {
      try {
        boolean hasMessages = false;
        Folder next;
        do {
          if (lastFolder != null) {
            lastFolder.close(false);
            lastFolder = null;
          }
          if (folders.isEmpty()) {
            mailbox.close();
            return null;
          }
          next = folders.remove(0);
          if (next != null) {
            String fullName = next.getFullName();
            if (!excludeFolder(fullName)) {
              hasMessages = (next.getType() & Folder.HOLDS_MESSAGES) != 0;
              next.open(Folder.READ_ONLY);
              lastFolder = next;
              LOG.info("Opened folder: {}", fullName);
            }
            if (((next.getType() & Folder.HOLDS_FOLDERS) != 0)) {
              Folder[] children = next.list();
              LOG.debug("Adding its children to list");
              for (int i = children.length - 1; i >= 0; i--) {
                folders.add(0, children[i]);
                LOG.debug("Child name: {}", children[i].getFullName());
              }
              if (children.length == 0) {
                LOG.debug("No children");
              }
            }
          }
        } while (!hasMessages);
        return next;
      } catch (MessagingException e) {
        return null;
      }
    }

    public void remove() {
      throw new UnsupportedOperationException("Its read only mode.");
    }

    private void getTopLevelFolders(Store mailBox) throws EmailFetchException {
      try {
        folders.add(mailBox.getDefaultFolder());
      } catch (MessagingException e) {
        throw new EmailFetchException("Folder retreival failed", e);
      }
    }

    private boolean excludeFolder(String name) {
      for (String s : excludes) {
        if (name.matches(s))
          return true;
      }
      for (String s : includes) {
        if (name.matches(s))
          return false;
      }
      return includes.length > 0;
    }
  }

  class MessageIterator implements Iterator<Message> {
    private Folder folder;
    private Message[] messagesInCurBatch;
    private int current = 0;
    private int currentBatch = 0;
    private int lastIndex = 0;
    private int batchSize = 0;
    private int totalInFolder = 0;
    private boolean doBatching = true;

    public MessageIterator(Folder folder, int batchSize) throws EmailFetchException {
      try {
        this.folder = folder;
        this.batchSize = batchSize;
        SearchTerm st = getSearchTerm();
        if (st != null) {
          doBatching = false;
          messagesInCurBatch = folder.search(st);
          totalInFolder = messagesInCurBatch.length;
          folder.fetch(messagesInCurBatch, fp);
          current = 0;
          LOG.info("Total messages: {}", totalInFolder);
          LOG.info("Search criteria applied. Batching disabled.");
        } else {
          totalInFolder = folder.getMessageCount();
          LOG.info("Total messages: {}", totalInFolder);
          getNextBatch(batchSize, folder);
        }
      } catch (MessagingException e) {
        throw new EmailFetchException("Message retreival failed", e);
      }
    }

    private void getNextBatch(int batchSize, Folder folder) throws MessagingException {
      // after each batch invalidate cache
      if (messagesInCurBatch != null) {
        for (Message m : messagesInCurBatch) {
          if (m instanceof IMAPMessage) {
            ((IMAPMessage) m).invalidateHeaders();
          }
        }
      }
      int lastMsg = lastIndex + (currentBatch + 1) * batchSize;
      lastMsg = lastMsg > totalInFolder ? totalInFolder : lastMsg;
      messagesInCurBatch = folder.getMessages(lastIndex + currentBatch * batchSize + 1, lastMsg);
      folder.fetch(messagesInCurBatch, fp);
      current = 0;
      currentBatch++;
      LOG.info("Current batch: {}", currentBatch);
      LOG.info("Messages in this batch: {}", messagesInCurBatch.length);
    }

    public boolean hasNext() {
      boolean hasMore = current < messagesInCurBatch.length;
      if (!hasMore && doBatching && lastIndex + currentBatch * batchSize < totalInFolder) {
        // try next batch
        try {
          getNextBatch(batchSize, folder);
          hasMore = current < messagesInCurBatch.length;
        } catch (MessagingException e) {
          LOG.error("Message retreival failed");
        }
      }
      return hasMore;
    }
    
    private SearchTerm getSearchTerm() {
      SearchTerm st = null;
      
      if (fetchMailsSince != null) {
        CustomFilter dateFilter = new MailsSinceLastCheckFilter(fetchMailsSince);
        st = dateFilter.getCustomSearch(folder);
      }
      
      if (keywords != null && !keywords.isEmpty()) {
        SearchTerm[] terms = new SearchTerm[keywords.size() * 2];
        int i = 0;
        for (String keyword : keywords) {
          terms[i++] = new SubjectTerm(keyword);
          terms[i++] = new BodyTerm(keyword);
        }
  
        OrTerm orTerm = new OrTerm(terms);
        
        if (st == null) {
          st = orTerm;
        } else {
          st = new AndTerm(st, orTerm);
        }
      }
      
      return st;
    }

    public String getFolder() {
      return this.folder.getFullName();
    }

    public Message next() {
      return hasNext() ? messagesInCurBatch[current++] : null;
    }

    public void remove() {
      throw new UnsupportedOperationException("Its read only mode...");
    }
  }

  public IMapFetcher(CompositeConfiguration config, String[] includes, String[] excludes, Date fetchMailsSince) {
    this.config = config;
    this.includes = includes;
    this.excludes = excludes;
    this.fetchMailsSince = fetchMailsSince;
  }

  public boolean connectToMailBox() {
    try {
      Properties props = new Properties();
      props.setProperty("mail.imap.ssl.enable", "true"); // required for Gmail
      props.setProperty("mail.imap.auth.mechanisms", "XOAUTH2");
      props.setProperty("mail.store.protocol", config.getString("imap.protocol"));
      props.setProperty("mail.imaps.fetchsize", "" + fetchSize);
      props.setProperty("mail.imaps.timeout", "" + rTimeout);
      props.setProperty("mail.imaps.writetimeout", "" + rTimeout);
      props.setProperty("mail.imaps.connectiontimeout", "" + cTimeout);
      props.setProperty("mail.imaps.connectionpooltimeout", "" + cTimeout);

      Session session = Session.getInstance(props);
      mailbox = session.getStore(config.getString("imap.protocol"));
      mailbox.connect(config.getString("imap.host"), config.getString("imap.user"), config.getString("imap.access_token"));
      LOG.info("Connected to mailbox");
      return true;
    } catch (MessagingException e) {
      LOG.error("Connection failed", e);
      return false;
    }
  }

  public boolean disconnectFromMailBox() {
    folderIter = null;
    msgIter = null;
    try {
      mailbox.close();
      LOG.info("Disconnected from mailbox");
      return true;
    } catch (MessagingException e) {
      LOG.error("Connection failed", e);
      return false;
    }
  }

  public void setFilterKeywords(List<String> keywords) {
    this.keywords = keywords;
  }

  public boolean hasNext() {
    try {
      if (folderIter == null) {
        folderIter = new FolderIterator(mailbox);
      }
      // get next message from the folder
      // if folder is exhausted get next folder
      // loop till a valid mail or all folders exhausted.
      while (msgIter == null || !msgIter.hasNext()) {
        Folder next = folderIter.hasNext() ? folderIter.next() : null;
        if (next == null) {
          return false;
        }
        msgIter = new MessageIterator(next, batchSize);
      }
    } catch (EmailFetchException e) {
      LOG.error("Fetching email failed", e);
      return false;
    }
    return true;
  }

  public void remove() {
    throw new UnsupportedOperationException("Its read only mode.");
  }

  public IMAPMessage next() {
    return (IMAPMessage) msgIter.next();
  }

  public String getFolder() {
    if (msgIter == null) {
      return null;
    }
    return msgIter.getFolder();
  }
  
  public boolean jumpToMessageId(String id) {
    while (hasNext()) {
      IMAPMessage mail = next();
      
      try {
        if (id.equals(mail.getMessageID())) {
          return true;
        }
      } catch (MessagingException e) {
        return false;
      }
    }
    return false;
  }

  public boolean jumpToFolder(String folderName) {
    FolderIterator newFolderIter = null;
    MessageIterator newMsgIter = null;
    try {
      newFolderIter = new FolderIterator(mailbox);

      while (newFolderIter.hasNext()) {
        Folder next = newFolderIter.next();
        if (folderName.equals(next.getFullName())) {
          newMsgIter = new MessageIterator(next, batchSize);
          folderIter = newFolderIter;
          msgIter = newMsgIter;
          return true;
        }
      }

      return false;
    } catch (EmailFetchException e) {
      LOG.error("Fetching email failed", e);
      return false;
    }
  }

  public void getPartContent(Part part, StringBuilder sb) throws Exception {
    if (part.isMimeType("text/*")) {
      String s = (String) part.getContent();
      if (s != null) {
        sb.append(s).append(" ");
      }
    } else if (part.isMimeType("multipart/*")) {
      Multipart mp = (Multipart) part.getContent();
      int count = mp.getCount();
      if (part.isMimeType("multipart/alternative")) {
        count = 1;
      }

      for (int i = 0; i < count; i++) {
        getPartContent(mp.getBodyPart(i), sb);
      }
    } else if (part.isMimeType("message/rfc822")) {
      getPartContent((Part) part.getContent(), sb);
    }
  }
  
  public static interface CustomFilter {
    public SearchTerm getCustomSearch(Folder folder);
  }
  
  class MailsSinceLastCheckFilter implements CustomFilter {

    private Date since;

    public MailsSinceLastCheckFilter(Date date) {
      since = date;
    }

    @SuppressWarnings("serial")
    public SearchTerm getCustomSearch(final Folder folder) {
      final SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd"); 
      final String sinceText = new SimpleDateFormat("yyyy-MM-dd").format(since);
      
      LOG.info("Building mail filter for messages in {} that occur from {}", folder.getName(), sinceText);
      return new DateTerm(ComparisonTerm.GE, since) {
        private int matched = 0;
        private int seen = 0;
        
        @Override
        public boolean match(Message msg) {
          boolean isMatch = false;
          ++seen;
          try {
            Date msgDate = msg.getReceivedDate();
            if (msgDate == null) msgDate = msg.getSentDate();
            
            if (msgDate != null && msgDate.getTime() >= since.getTime()) {
              ++matched;
              isMatch = true;
            } else {
              if (LOG.isDebugEnabled()) {
                String msgDateStr = (msgDate != null) ? parser.format(msgDate) : "null";
                String sinceDateStr = (since != null) ? sinceText : "null";
                LOG.debug("Message {} was received at [{}], since filter is [{}]", msg.getSubject(), msgDateStr, sinceDateStr);
              }
            }
          } catch (MessagingException e) {
            LOG.warn("Failed to process message", e);
          }
          
          if (seen % 100 == 0) {
            LOG.info("Matched {} of {} messages since {}", matched, seen, sinceText);
          }
          
          return isMatch;
        }
      };
    }
  }
}

