## Github Code Search
A fork of mhilmiasyrofi/github-code-search.
Several modifications were made for my own ease-of-use. The queries and github access tokens are now passed in as command line arguments.
We only perform one query for each run, but more keywords can be added to the search query as needed.

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



java -cp target/github-code-search-1.0-SNAPSHOT-jar-with-dependencies.jar com.project.githubsearch.App "java.io.ByteArrayOutputStream#toByteArray()" <# types of files> <access token>

`# types of files` are the number of unique source files you expect to receive. It is expected that github's search results returns code clones, 
but clones of already-seen files are uninteresting so these are discarded. The total number of clones we see for each unique file is counted and printed to standard output in the end.

If the results are too broad, further keywords can be added to the search query. 
Note that these keywords are treated directly as text, i.e. they are neither type-resolved nor is there a guarantee that they appear in the search results. 
We simply pass them into the query for Github's search, which is a black-box to us.


