package ru.demoneach.dbgenerator.entity;

public class ConnectionParameters {
    private String host;
    private String port;
    private String username;
    private String password;
    private String dbName;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public String getPort() {
        return port;
    }

    public void setPort(String port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((host == null) ? 0 : host.hashCode());
        result = prime * result + ((port == null) ? 0 : port.hashCode());
        result = prime * result + ((username == null) ? 0 : username.hashCode());
        result = prime * result + ((password == null) ? 0 : password.hashCode());
        result = prime * result + ((dbName == null) ? 0 : dbName.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        ConnectionParameters other = (ConnectionParameters) obj;
        if (host == null) {
            if (other.host != null) return false;
        } else if (!host.equals(other.host)) return false;
        if (port == null) {
            if (other.port != null) return false;
        } else if (!port.equals(other.port)) return false;
        if (username == null) {
            if (other.username != null) return false;
        } else if (!username.equals(other.username)) return false;
        if (password == null) {
            if (other.password != null) return false;
        } else if (!password.equals(other.password)) return false;
        if (dbName == null) {
            if (other.dbName != null) return false;
        } else if (!dbName.equals(other.dbName)) return false;
        return true;
    }

    @Override
    public String toString() {
        return "ConnectionParameters [host=" + host + ", port=" + port + ", userName=" + username + ", password=" + password + ", dbName=" + dbName + "]";
    }

}
