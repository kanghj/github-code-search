## Github Code Search
A fork of mhilmiasyrofi/github-code-search.
Several modifications were made for my own ease-of-use. 
The queries and github access tokens are now passed in as command line arguments.
We only perform one query for each run, but more keywords can be added to the search query as needed to constrain the search.

A deduplication process is performed for performance reasons. The cost of type resolution is high.

## Prerequisite

- JDK8
- Maven3
- GitHub OAuth Token

## Getting Started

Visit this [link](https://github.com/settings/tokens) to create a github access token. 


## How to Run

```
<go to project directory>

mvn clean compile assembly:single

```


java -cp target/github-code-search-1.0-SNAPSHOT-jar-with-dependencies.jar com.project.githubsearch.App "<fully qualified class name>#<method name>()" <# types of files> <access token> <split by size>

```
java -cp target/github-code-search-1.0-SNAPSHOT-jar-with-dependencies.jar com.project.githubsearch.App "java.io.ByteArrayOutputStream#toByteArray()" 10 <access token> true

java -cp target/github-code-search-1.0-SNAPSHOT-jar-with-dependencies.jar com.project.githubsearch.App "java.util.Map#get(x)" 10 <access token> true
```


One modification I made was to ignore the type of the arguments. Now, just pass in a bunch of strings (e.g. get(x), or <init>(x,y)
`# types of files` are the number of unique source files you expect to receive. 
It is expected that github's search results returns code clones, 
but clones of already-seen files are uninteresting so these are discarded. The total number of clones we see for each unique file is counted and printed to standard output towards the end.

If the results are too broad for your liking, further keywords can be added to the search query. 
Note that these keywords are treated directly as text, i.e. they are neither type-resolved nor is there a guarantee that they appear in the search results. 
We simply pass them into the query for Github's search, which is a black-box to us.
Github will use these keywords in its own query, and therefore likely consider files containing these keywords more relevant.


