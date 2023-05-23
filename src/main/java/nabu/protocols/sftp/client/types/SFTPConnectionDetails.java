package nabu.protocols.sftp.client.types;

public class SFTPConnectionDetails {
	private boolean connected;
	private String connectionId;

	public SFTPConnectionDetails() {
		// auto
	}
	public SFTPConnectionDetails(String connectionId, boolean connected) {
		this.connectionId = connectionId;
		this.connected = connected;
	}
	
	public String getConnectionId() {
		return connectionId;
	}
	public void setConnectionId(String connectionId) {
		this.connectionId = connectionId;
	}
	public boolean isConnected() {
		return connected;
	}
	public void setConnected(boolean connected) {
		this.connected = connected;
	}
}
