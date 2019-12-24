package com.project.githubsearch.model;
import java.util.Optional;

import org.json.JSONArray;

public class Response {
    private int code;
    private int total_count;
    private JSONArray item;
    private String url_request;
    private Optional<String> next_url_request;

    public Response() {
        this.code = 0;
        this.total_count = 0;
        this.item = new JSONArray();
        this.url_request = null;
        this.next_url_request = Optional.empty();
    }
    
    // getter
    public int getCode() {
        return this.code;
    }

    public int getTotalCount() {
        return this.total_count;
    }
    
    public String getUrlRequest() {
        return this.url_request;
    }
    public Optional<String> getNextUrlRequest() {
        return this.next_url_request;
    }

    public JSONArray getItem() {
        return this.item;
    }

    // setter
    public void setCode(int code) {
        this.code = code;
    }

    public void setTotalCount(int total_count) {
        this.total_count = total_count;
    }

    public void setNextUrlRequest(Optional<String> next_url_request) {
        this.next_url_request = next_url_request;
    }

    public void setUrlRequest(String url_request) {
        this.url_request = url_request;
    }

    public void setItem(JSONArray item) {
        this.item = item;
    }

}