package com.github.snaptool.config.data;

public class Repo {
    private String repo;
    private RepoParams params;
    public String getRepo() {
        return repo;
    }
    public void setRepo(String repo) {
        this.repo = repo;
    }
    public RepoParams getParams() {
        return params;
    }
    public void setParams(RepoParams params) {
        this.params = params;
    }
    public Repo() {}
    @Override
    public String toString() {
        return "Repo [repo=" + repo + ", params=" + params + "]";
    }

    
    
}
