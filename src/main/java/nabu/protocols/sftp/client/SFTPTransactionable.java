/*
* Copyright (C) 2023 Alexander Verbruggen
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU Lesser General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
* GNU Lesser General Public License for more details.
*
* You should have received a copy of the GNU Lesser General Public License
* along with this program. If not, see <https://www.gnu.org/licenses/>.
*/

package nabu.protocols.sftp.client;

import javax.xml.bind.annotation.XmlTransient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.ChannelSftp;

import be.nabu.libs.services.api.Transactionable;

public class SFTPTransactionable implements Transactionable {

	private Logger logger = LoggerFactory.getLogger(getClass());
	private String id;
	private ChannelSftp channel;
	private String host;
	private int port;
	private boolean closed;

	public SFTPTransactionable(String id, ChannelSftp channel) {
		this.id = id;
		this.channel = channel;
	}
	
	@Override
	public String getId() {
		return id;
	}
	public void setId(String id) {
		this.id = id;
	}

	@Override
	public void start() {
		// do nothing
	}

	@Override
	public void commit() {
		closed = true;
		if (channel.isConnected()) {
			try {
				try {
					channel.exit();
				}
				catch (Exception e) {
					// don't care
					logger.warn("Could not close the sftp channel", e);
				}
				channel.getSession().disconnect();
			}
			catch (Exception e) {
				// don't care
				logger.warn("Could not close the sftp channel", e);
			}
		}
	}

	@Override
	public void rollback() {
		this.commit();
	}

	@XmlTransient
	public ChannelSftp getChannel() {
		return channel;
	}

	public boolean isClosed() {
		return closed;
	}
	public void setClosed(boolean closed) {
		this.closed = closed;
	}
	
	public String getHost() {
		return host;
	}
	public void setHost(String host) {
		this.host = host;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}
	
}
