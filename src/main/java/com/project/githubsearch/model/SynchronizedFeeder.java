package com.project.githubsearch.model;

import java.util.ArrayList;

// despite allowing multiple auth tokens now, we do not use it.
public class SynchronizedFeeder {
    ArrayList<GithubToken> tokens;
    
    public SynchronizedFeeder(String[] tokens) {
        this.tokens = new ArrayList<GithubToken>();
        // please make sure that the number of thread is equal with the number of tokens
//        this.tokens.add(new GithubToken(System.getenv("GITHUB_AUTH_TOKEN_1")));
//        this.tokens.add(new GithubToken(System.getenv("GITHUB_AUTH_TOKEN_2")));
//        this.tokens.add(new GithubToken(System.getenv("GITHUB_AUTH_TOKEN_3")));
        
        for (int i = 0; i < tokens.length; i++) {
        	this.tokens.add(new GithubToken(tokens[i]));
        }
    }

    public synchronized GithubToken getAvailableGithubToken() {
        GithubToken token = this.tokens.get(0);
        for (int i = 0; i < this.tokens.size(); i++) {
            if (!this.tokens.get(i).getUsed()) {
                this.tokens.get(i).setUsed(true);
                return tokens.get(i);
            } 
        }
        return token;
    }

    public synchronized void releaseToken(GithubToken token) {
        for (int i = 0; i < this.tokens.size(); i++) {
            if (this.tokens.get(i).getToken().equals(token.getToken())) {
                this.tokens.get(i).setUsed(false);
                break;
            }
        }
    }
    
}