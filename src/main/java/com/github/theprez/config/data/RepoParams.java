package com.github.theprez.config.data;

public class RepoParams {
	private String url;
	private int concurrency;

	public String getUrl() {
		return url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public int getConcurrency() {
		return concurrency;
	}

	public void setConcurrency(int concurrency) {
		this.concurrency = concurrency;
	}


	public RepoParams() {}

    @Override
    public String toString() {
        return "RepoParams [url=" + url + ", concurrency=" + concurrency + "]";
    }
    
}