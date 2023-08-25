package nabu.protocols.sftp.client;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLConnection;
import java.security.Key;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Vector;

import javax.jws.WebParam;
import javax.jws.WebResult;
import javax.jws.WebService;
import javax.validation.constraints.NotNull;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.ChannelSftp.LsEntry;

import be.nabu.libs.resources.URIUtils;
import be.nabu.libs.resources.api.Resource;
import be.nabu.libs.resources.api.ResourceProperties;
import be.nabu.libs.resources.impl.ResourcePropertiesImpl;
import be.nabu.libs.services.api.ExecutionContext;
import be.nabu.libs.services.api.Transactionable;
import nabu.protocols.sftp.client.types.SFTPConnectionDetails;

@WebService
public class Services {

	private ExecutionContext executionContext;
	
	@WebResult(name = "connection")
	// the control defaults to 300s (5 minutes)
	public SFTPConnectionDetails connect(@WebParam(name = "transactionId") String transactionId, 
			@NotNull @WebParam(name = "host") String host,
			@NotNull @WebParam(name = "port") Integer port,
			@WebParam(name = "username") String username, 
			@WebParam(name = "password") String password,
			@WebParam(name = "privateKey") Key privateKey) throws SocketException, IOException, JSchException {
		
		String key = UUID.randomUUID().toString().replace("-", "");
		
		JSch jsch = new JSch();
//		jsch.setKnownHosts(stream);
		
		Session session = jsch.getSession(username, host, port == null ? 22 : port);
		if (password != null) {
			session.setPassword(password);
		}
		session.setConfig("StrictHostKeyChecking", "no");

		session.connect();

		// set to 5 minutes
		session.setServerAliveInterval(300000);
		
		ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
		channel.connect();
		
		SFTPTransactionable transactionable = new SFTPTransactionable(key, channel);
		executionContext.getTransactionContext().push(transactionId, transactionable);
		transactionable.setHost(host);
		transactionable.setPort(port);

		return new SFTPConnectionDetails(key, channel.isConnected());
	}
	
	private SFTPTransactionable retrieve(String id) {
		for (String transactionId : executionContext.getTransactionContext()) {
			Transactionable transactionable = executionContext.getTransactionContext().get(transactionId, id);
			if (transactionable instanceof SFTPTransactionable) {
				return (SFTPTransactionable) transactionable;
			}
		}
		return null;
	}
	
	private String getPath(URI uri, SFTPTransactionable transactionable) {
		if (uri == null) {
			return "/";
		}
		if (uri.getScheme() != null && !"sftp".equalsIgnoreCase(uri.getScheme())) {
			throw new IllegalArgumentException("Only scheme sftp is supported");
		}
		if (uri.getHost() != null && !uri.getHost().equalsIgnoreCase(transactionable.getHost())) {
			throw new IllegalArgumentException("The host of the URI does not match the current connection: " + uri.getHost() + " != " + transactionable.getHost());
		}
		if (uri.getPort() > 0 && uri.getPort() != transactionable.getPort()) {
			throw new IllegalArgumentException("The port of the URI does not match the current connection: " + uri.getPort() + " != " + transactionable.getPort());
		}
		// only check the port if the host is available
		else if (uri.getHost() != null && uri.getPort() < 0 && uri.getPort() != 22) {
			throw new IllegalArgumentException("The port of the URI does not match the current connection: 22 != " + transactionable.getPort());
		}
		return uri.getPath() == null ? "/" : uri.getPath();
	}
	
	@WebResult(name = "entries")
	public List<ResourceProperties> list(@WebParam(name = "connectionId") @NotNull String connectionId, @WebParam(name = "uri") URI uri, @WebParam(name = "regex") final String regex) throws IOException, SftpException {
		if (connectionId == null) {
			throw new IllegalArgumentException("You must send a valid ftp connection id");
		}
		SFTPTransactionable transactionable = retrieve(connectionId);
		List<ResourceProperties> entries = new ArrayList<ResourceProperties>();
		String path = getPath(uri, transactionable);
		Vector<LsEntry> list = transactionable.getChannel().ls(path);
		if (list != null) {
			for (LsEntry child : list) {
				if (child.getFilename().equals(".") || child.getFilename().equals("..")) {
					continue;
				}
				if (regex != null && !child.getFilename().matches(regex)) {
					continue;
				}
				String childPath = (path == null ? child.getFilename() : path + "/" + child.getFilename());
				entries.add(toProperties(childPath, child.getAttrs(), transactionable));
			}
		}
		return entries;
	}

	public void write(@WebParam(name = "connectionId") @NotNull String connectionId, @NotNull @WebParam(name = "uri") URI uri, @NotNull @WebParam(name = "stream") InputStream input) throws IOException, SftpException {
		if (connectionId == null) {
			throw new IllegalArgumentException("You must send a valid sftp connection id");
		}
		SFTPTransactionable transactionable = retrieve(connectionId);
		if (transactionable == null) {
			throw new IllegalStateException("No such sftp connection found");
		}
		String path = getPath(uri, transactionable);
		String parentPath = path.replaceAll("/[^/]+$", "");
		// if we have a parent path, make sure it exists
		if (!parentPath.isEmpty()) {
			mkdirs(transactionable.getChannel(), parentPath);
		}
		transactionable.getChannel().put(new BufferedInputStream(input), path, ChannelSftp.OVERWRITE);
	}

	private void mkdirs(ChannelSftp channel, String parentPath) throws SftpException {
		StringBuilder builder = new StringBuilder();
		boolean first = true;
		for (String part : parentPath.split("/")) {
			if (part.equals(".")) {
				continue;
			}
			else if (part.equals("..")) {
				throw new IllegalArgumentException("Can't browse up in: " + parentPath);
			}
			// could be leading or trailing slashes
			else if (part.trim().isEmpty()) {
				continue;
			}
			if (first) {
				first = false;
			}
			else {
				builder.append("/");
			}
			builder.append(part);
			boolean exists = true;
			try {
				SftpATTRS stat = channel.stat(builder.toString());
				if (!stat.isDir()) {
					exists = false;
				}
			}
			catch (Exception e) {
				exists = false;
			}
			if (!exists) {
				channel.mkdir(builder.toString());
			}
		}
	}
	
	public ResourceProperties properties(@WebParam(name = "connectionId") @NotNull String connectionId, @NotNull @WebParam(name = "uri") URI uri) throws IOException, SftpException {
		SFTPTransactionable transactionable = retrieve(connectionId);
		if (transactionable == null) {
			throw new IllegalStateException("No such sftp connection found");
		}
		String path = getPath(uri, transactionable);
		return toProperties(path, transactionable.getChannel().stat(path), transactionable);
	}
	
	private ResourceProperties toProperties(String path, SftpATTRS stat, SFTPTransactionable transactionable) {
		// replace double slashes
		path = path.replaceAll("[/]{2,}", "/");
		ResourcePropertiesImpl properties = new ResourcePropertiesImpl();
		try {
			if (!path.startsWith("/")) {
				path = "/" + path;
			}
			properties.setUri(new URI("sftp", null, transactionable.getHost(), transactionable.getPort(), path, null, null));
		}
		catch (URISyntaxException e) {
			throw new RuntimeException(e);
		}
		properties.setName(path.replaceAll("^.*?/([^/]+)$", "$1"));
		if (stat.isDir()) {
			properties.setContentType(Resource.CONTENT_TYPE_DIRECTORY);
		}
		else {
			properties.setContentType(URLConnection.guessContentTypeFromName(properties.getName()));
			properties.setSize(stat.getSize());
		}
		properties.setLastAccessed(new Date(stat.getATime() * 1000l));
		properties.setLastModified(new Date(stat.getMTime() * 1000l));
		return properties;
	}
	
	@WebResult(name = "stream")
	public InputStream read(@WebParam(name = "connectionId") @NotNull String connectionId, @NotNull @WebParam(name = "uri") URI uri) throws IOException, SftpException {
		if (connectionId == null) {
			throw new IllegalArgumentException("You must send a valid sftp connection id");
		}
		SFTPTransactionable transactionable = retrieve(connectionId);
		if (transactionable == null) {
			throw new IllegalStateException("No such sftp connection found");
		}
		String path = getPath(uri, transactionable);
		return new BufferedInputStream(transactionable.getChannel().get(path));
	}
	
	public void delete(@WebParam(name = "connectionId") @NotNull String connectionId, @NotNull @WebParam(name = "uri") URI uri) throws IOException, SftpException {
		if (connectionId == null) {
			throw new IllegalArgumentException("You must send a valid sftp connection id");
		}
		SFTPTransactionable transactionable = retrieve(connectionId);
		if (transactionable == null) {
			throw new IllegalStateException("No such sftp connection found");
		}
		String path = getPath(uri, transactionable);
		if (path.endsWith("/")) {
			transactionable.getChannel().rmdir(path);
		}
		else {
			transactionable.getChannel().rm(path);
		}
	}
	
	public void rename(@WebParam(name = "connectionId") @NotNull String connectionId, @NotNull @WebParam(name = "uri") URI uri, @NotNull @WebParam(name = "newName") String newName) throws IOException, SftpException {
		if (connectionId == null) {
			throw new IllegalArgumentException("You must send a valid sftp connection id");
		}
		SFTPTransactionable transactionable = retrieve(connectionId);
		if (transactionable == null) {
			throw new IllegalStateException("No such sftp connection found");
		}
		String path = getPath(uri, transactionable);
		String newPath = path.indexOf('/') < 0 ? newName : path.replaceAll("^(.*?/)[^/]+$", "$1" + newName);
		transactionable.getChannel().rename(
			path, 
			newPath
		);
	}
}
