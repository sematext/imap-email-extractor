# imap-email-extractor

Extract Email addresses using IMAP.  

## Setting up for OAuth authentication
The specific steps for the first-time setup look like this:
1. Go to https://console.developers.google.com/ and create a new "project". You will need a Google account, which may or may not be the same as the one you intend to send mail from.
2. When the project dashboard comes up for the new project, select "Enable and manage APIs"
3. Select "Credentials" on the left
4. Select "OAuth Consent Screen" on the right
5. Enter a product name in "Product name shown to users". When you need to approve access to the GMail account, you'll see a message like "Do you want to allow [Product Name] to have full control over your GMail account?" so it should be a name someone will recognize.
6. Hit "Save" and go to the "Credentials" tab
7. Hit "Create Credentials" then "OAuth Client ID"
8. Select the application type "Other" and then enter a name or description for the application (this one is not shown to users)
9. This gives you a popup with your Client ID and Client Secret. Save both of those. You will need them for all the future steps.
10. Generate a refresh key and your first access key. If you can run Python scripts, the easiest way is to run the [oauth2.py file here](https://github.com/google/gmail-oauth2-tools/tree/master/python). The top of the file has the parameters you need to pass when you run it, under "1. The first mode is used to generate and authorize an OAuth2 token." You'll need the GMail account you want to send mail from, and the Client ID and Client Secret generated above.
11. When you run the Python script with that syntax, it will tell you to visit a Web URL. Copy and paste that into a browser. That's where you'll get the prompt for whether you want to allow the application [Product Name] to have full control over the GMail account in question. When that is approved, it will generate another text string for you.
```bash
python oauth2.py --user=XXX@YYY.com  --client_id=your_client_id --client_secret=your_client_secret --generate_oauth2_token
```
12. Copy that text string back into the waiting Python application. At that point it will emit the refresh token, your first access token, and the lifetime (in seconds) of the access token. Save at least the refresh token.

You should do all this only once. You will be left with a Client ID, Client Secret, and Refresh Token, all of which are strings of text. You can save the first Access Token if you like; if you do it's worth noting the current time so you can calculate when it will expire.

## Installation

Update IMAP info (user/access_token) in src/main/resources/config.properties

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