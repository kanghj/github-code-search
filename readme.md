# Github Code Search (AUSearch Backend)

The primary repository for AUSearch can be found at https://github.com/mhilmiasyrofi/ausearch. The accompanying paper, **AUSearch: Accurate API Usage Search in GitHub Repositories with Type Resolution**, published at SANER 2020, is at https://github.com/mhilmiasyrofi/ausearch/blob/master/SANER_2020_AUSearch.pdf.

### Find API usage example here!
A CLI apps that helps you to find some API usage examples from java source code. Given an API query that allows type constraints, This tool will find code examples in GitHub that contain usages of the specific APIs in the query. This tool performs type resolutions to ensure that the API usages found in the returned files are indeed invocations of the APIs specified in the query and highlights the relevant lines of code in the files for easier reference.

## Prerequisite

- [Java Development Kit (JDK)](https://www.oracle.com/technetwork/java/javase/downloads/index.html), version 1.8.
- [Apache Maven](https://maven.apache.org/), version 3.0 or later.
- [GitHub OAuth Token](https://github.com/settings/tokens). Please read the **Getting Started** part carefully :)

## Getting Started

First, you should set some Github OAuth tokens into your laptop/computer environment variable, like this:
```
export GITHUB_AUTH_TOKEN_1=xxxxxxx
export GITHUB_AUTH_TOKEN_2=xxxxxxx
export GITHUB_AUTH_TOKEN_3=xxxxxxx
```
Visit this [link](https://github.com/settings/tokens) to create it. If you just have one token only, please write your token in each environment variable. It will works also :). 


## How to Run

```
<go to your project directory>

mvn clean compile assembly:single

java -cp target/github-code-search-1.0-SNAPSHOT-jar-with-dependencies.jar com.project.githubsearch.App
```

Please type your query then submit it!

## API Query
This app will help you if you type the query correctly. So read this part carefully :). The query consist of 3 main parts; fully qualified name, method, and its parameter.
Some accepted queries example:
#### 1. One query without parameter
```
android.net.ConnectivityManager#getAllNetworkInfo()
```
#### 2. One query with one parameter
```
android.app.Notification.Builder#addAction(android.app.Notification.Action)
```
#### 3. One query with two parameters or more
```
android.app.Notification.Builder#addAction(int, java.lang.CharSequence, android.app.PendingIntent)
```
#### 4. Multiple queries
```
android.os.Vibrator#vibrate(long)&android.location.LocationManager#removeGpsStatusListener(android.location.GpsStatus.Listener)
```


## Developer Mark 
**Note** that this apps is already tested on Ubuntu and Mac OS. Unfortunately, this doesn't work well on Microsoft shell because of the multi-threading part. Don't worry, we still find the solution for this. If you find a problem while using this apps, please notify me via [this](mhilmia@smu.edu.sg) email. I will help you soon to ensure that you can try this amazing apps immediately :). s
