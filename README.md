# imap-email-extractor

Extract Email addresses using IMAP.  

## Installation

Update IMAP info (user/pass) in src/main/resources/config.properties

# Build

```
mvn package
```

# Commands

```
Usage: java -jar target/imap-email-extractor-0.0.1-SNAPSHOT-jar-with-dependencies.jar -i [folders] -e [folders] -h
  where OPTIONS may be:
    -h              Print this help
    -i <folders>    OPTIONAL Comma-separated list of include folders (regular expression)
    -e <folders>    OPTIONAL Comma-separated list of exclude folders (regular expression)
```