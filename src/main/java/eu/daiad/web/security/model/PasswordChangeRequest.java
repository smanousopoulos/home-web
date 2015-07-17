package eu.daiad.web.security.model;

import eu.daiad.web.model.AuthenticatedRequest;

public class PasswordChangeRequest extends AuthenticatedRequest{

	private String password;

	public String getPassword() {
		if(password == null) {
			return "";
		}
		return password.trim();
	}

	public void setPassword(String password) {
		this.password = password;
	}
	
	
}