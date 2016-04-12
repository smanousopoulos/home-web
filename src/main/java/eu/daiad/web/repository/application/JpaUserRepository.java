package eu.daiad.web.repository.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.persistence.EntityManager;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import eu.daiad.web.domain.application.AccountProfile;
import eu.daiad.web.domain.application.AccountProfileHistoryEntry;
import eu.daiad.web.domain.application.AccountRole;
import eu.daiad.web.domain.application.AccountWhiteListEntry;
import eu.daiad.web.domain.application.Role;
import eu.daiad.web.domain.application.Utility;
import eu.daiad.web.model.EnumValueDescription;
import eu.daiad.web.model.error.ApplicationException;
import eu.daiad.web.model.error.SharedErrorCode;
import eu.daiad.web.model.error.UserErrorCode;
import eu.daiad.web.model.profile.EnumMobileMode;
import eu.daiad.web.model.profile.EnumUtilityMode;
import eu.daiad.web.model.profile.EnumWebMode;
import eu.daiad.web.model.security.AuthenticatedUser;
import eu.daiad.web.model.security.EnumRole;
import eu.daiad.web.model.user.Account;

@Repository
@Transactional("transactionManager")
public class JpaUserRepository implements IUserRepository {

	private static final Log logger = LogFactory.getLog(JpaUserRepository.class);

	@Value("${security.white-list}")
	private boolean enforceWhiteListCheck;

	@PersistenceContext(unitName="default")
	EntityManager entityManager;

	private void initializeRoles() throws ApplicationException {
		try {
			for (EnumRole r : EnumRole.class.getEnumConstants()) {
				TypedQuery<Role> roleQuery = entityManager.createQuery("select r from role r where r.name = :name",
								Role.class);
				roleQuery.setParameter("name", r.toString());

				List<Role> roles = roleQuery.getResultList();
				if (roles.size() == 0) {
					Role role = new Role();

					String description = EnumRole.class.getField(r.name()).getAnnotation(EnumValueDescription.class)
									.value();
					role.setName(r.name());
					role.setDescription(description);

					this.entityManager.persist(role);
				}
			}
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, UserErrorCode.ROLE_INITIALIZATION);
		}
	}

	private void initializeAdministrators() throws ApplicationException {
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		try {
			TypedQuery<eu.daiad.web.domain.application.Utility> utilityQuery = entityManager.createQuery(
							"select u from utility u", eu.daiad.web.domain.application.Utility.class);

			for (eu.daiad.web.domain.application.Utility utility : utilityQuery.getResultList()) {
				TypedQuery<eu.daiad.web.domain.application.Account> userQuery = entityManager.createQuery(
								"select a from account a where a.username = :username",
								eu.daiad.web.domain.application.Account.class);
				userQuery.setParameter("username", utility.getDefaultAdministratorUsername());

				List<eu.daiad.web.domain.application.Account> users = userQuery.getResultList();

				if (users.size() == 0) {
					String password = UUID.randomUUID().toString();

					eu.daiad.web.domain.application.Account account = new eu.daiad.web.domain.application.Account();
					account.setUsername(utility.getDefaultAdministratorUsername());
					account.setPassword(encoder.encode(password));
					account.setLocked(false);
					account.setChangePasswordOnNextLogin(false);
					account.setUtility(utility);
					account.setLocale(Locale.ENGLISH.getLanguage());

					TypedQuery<Role> roleQuery = entityManager.createQuery("select r from role r where r.name = :name",
									Role.class);
					roleQuery.setParameter("name", EnumRole.ROLE_ADMIN.name());

					Role role = roleQuery.getSingleResult();

					AccountRole assignedRole = new AccountRole();
					assignedRole.setRole(role);
					assignedRole.setAssignedOn(account.getCreatedOn());
					assignedRole.setAssignedBy(account);

					account.getRoles().add(assignedRole);

					this.entityManager.persist(account);
					this.entityManager.flush();

					AccountProfile profile = new AccountProfile();
					profile.setMobileMode(EnumMobileMode.INACTIVE.getValue());
					profile.setWebMode(EnumWebMode.INACTIVE.getValue());
					profile.setUtilityMode(EnumUtilityMode.ACTIVE.getValue());
					profile.setUpdatedOn(account.getCreatedOn());

					profile.setAccount(account);
					this.entityManager.persist(profile);

					AccountProfileHistoryEntry entry = new AccountProfileHistoryEntry();
					entry.setVersion(profile.getVersion());
					entry.setUpdatedOn(account.getCreatedOn());
					entry.setMobileMode(profile.getMobileMode());
					entry.setWebMode(profile.getWebMode());
					entry.setUtilityMode(profile.getUtilityMode());

					entry.setProfile(profile);
					this.entityManager.persist(entry);

					logger.info(String
									.format("Default administrator has been crearted for utility [%s]. User name : %s. Password : %s",
													utility.getName(), utility.getDefaultAdministratorUsername(),
													password));
				}
			}
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, UserErrorCode.ADMIN_INITIALIZATION);
		}
	}

	@Override
	@Transactional(transactionManager = "transactionManager", propagation=Propagation.REQUIRES_NEW)
	public void initializeSecurityConfiguration() {
		try {
			// Initialize all system roles
			initializeRoles();

			// Create an administrator for any registered utility
			initializeAdministrators();
		} catch (ApplicationException ex) {
			logger.error("Database initialization has failed.", ex);
		}
	}

	private boolean isUsernameReserved(String username) {
		TypedQuery<eu.daiad.web.domain.application.Utility> userQuery = entityManager.createQuery(
						"select u from utility u where u.defaultAdministratorUsername = :username",
						eu.daiad.web.domain.application.Utility.class);

		userQuery.setParameter("username", username);

		return (userQuery.getResultList().size() != 0);
	}

	@Override
	public UUID createUser(Account user) throws ApplicationException {
		BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

		try {
			if (this.isUsernameReserved(user.getUsername())) {
				throw new ApplicationException(UserErrorCode.USERNANE_RESERVED).set("username", user.getUsername());
			}
			if (this.getUserByName(user.getUsername()) != null) {
				throw new ApplicationException(UserErrorCode.USERNANE_NOT_AVAILABLE)
								.set("username", user.getUsername());
			}

			AccountWhiteListEntry whiteListEntry = null;

			if (enforceWhiteListCheck) {
				TypedQuery<eu.daiad.web.domain.application.AccountWhiteListEntry> query = entityManager
								.createQuery("select a from account_white_list a where a.username = :username",
												eu.daiad.web.domain.application.AccountWhiteListEntry.class)
								.setFirstResult(0).setMaxResults(1);
				query.setParameter("username", user.getUsername());

				List<eu.daiad.web.domain.application.AccountWhiteListEntry> result = query.getResultList();
				if (result.size() == 0) {
					throw new ApplicationException(UserErrorCode.WHITELIST_MISMATCH)
									.set("username", user.getUsername());
				} else {
					whiteListEntry = result.get(0);
				}
			}

			Utility utility = null;

			if (whiteListEntry != null) {
				TypedQuery<eu.daiad.web.domain.application.Utility> query = entityManager.createQuery(
								"select u from utility u where u.id = :id",
								eu.daiad.web.domain.application.Utility.class);
				query.setParameter("id", whiteListEntry.getUtility().getId());

				utility = query.getSingleResult();
			} else {
				TypedQuery<eu.daiad.web.domain.application.Utility> query = entityManager.createQuery(
								"select u from utility u where u.name = :name",
								eu.daiad.web.domain.application.Utility.class);
				query.setParameter("name", "DAIAD");

				utility = query.getSingleResult();
			}

			eu.daiad.web.domain.application.Account account = new eu.daiad.web.domain.application.Account();
			account.setUsername(user.getUsername());
			account.setPassword(encoder.encode(user.getPassword()));

			account.setEmail(user.getUsername());

			if (whiteListEntry != null) {
				account.setFirstname(whiteListEntry.getFirstname());
				account.setLastname(whiteListEntry.getLastname());
				account.setBirthdate(whiteListEntry.getBirthdate());
				account.setGender(whiteListEntry.getGender());

				account.setLocale(whiteListEntry.getLocale());
				account.setCountry(whiteListEntry.getCountry());
				account.setCity(whiteListEntry.getCity());
				account.setAddress(whiteListEntry.getAddress());
				account.setTimezone(whiteListEntry.getTimezone());
				account.setPostalCode(whiteListEntry.getPostalCode());
			} else {
				account.setFirstname(user.getFirstname());
				account.setLastname(user.getLastname());
				account.setBirthdate(user.getBirthdate());
				account.setGender(user.getGender());

				account.setLocale(user.getLocale());
				account.setCountry(user.getCountry());
				account.setCity(user.getCity());
				account.setAddress(user.getAddress());
				account.setTimezone(user.getTimezone());
				account.setPostalCode(user.getPostalCode());
			}

			account.setLocked(false);
			account.setChangePasswordOnNextLogin(false);

			account.setUtility(utility);

			Role role = null;
			TypedQuery<Role> roleQuery = entityManager.createQuery("select r from role r where r.name = :name",
							Role.class);
			roleQuery.setParameter("name", EnumRole.ROLE_USER.toString());

			role = roleQuery.getSingleResult();

			AccountRole assignedRole = new AccountRole();
			assignedRole.setRole(role);
			assignedRole.setAssignedOn(account.getCreatedOn());

			account.getRoles().add(assignedRole);

			this.entityManager.persist(account);
			this.entityManager.flush();

			AccountProfile profile = new AccountProfile();
			if (whiteListEntry != null) {
				profile.setMobileMode(whiteListEntry.getDefaultMobileMode());
				profile.setWebMode(whiteListEntry.getDefaultWebMode());
			} else {
				profile.setMobileMode(EnumMobileMode.ACTIVE.getValue());
				profile.setWebMode(EnumWebMode.ACTIVE.getValue());
			}
			profile.setUtilityMode(EnumUtilityMode.INACTIVE.getValue());
			profile.setUpdatedOn(account.getCreatedOn());

			profile.setAccount(account);
			this.entityManager.persist(profile);

			AccountProfileHistoryEntry profileHistoryEntry = new AccountProfileHistoryEntry();
			profileHistoryEntry.setVersion(profile.getVersion());
			profileHistoryEntry.setUpdatedOn(account.getCreatedOn());
			profileHistoryEntry.setMobileMode(profile.getMobileMode());
			profileHistoryEntry.setWebMode(profile.getWebMode());
			profileHistoryEntry.setUtilityMode(profile.getUtilityMode());

			profileHistoryEntry.setProfile(profile);
			this.entityManager.persist(profileHistoryEntry);

			if (whiteListEntry != null) {
				whiteListEntry.setRegisteredOn(DateTime.now());
				whiteListEntry.setAccount(account);
			}

			return account.getKey();
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		}
	}

	@Override
	public void setPassword(String username, String password) throws ApplicationException {
		// TODO Auto-generated method stub

	}

	@Override
	public void setRole(String username, EnumRole role, boolean set) throws ApplicationException {
		// TODO Auto-generated method stub

	}

	@Override
	public AuthenticatedUser getUserByName(String username) throws ApplicationException {
		try {
			AuthenticatedUser user = null;

			TypedQuery<eu.daiad.web.domain.application.Account> query = entityManager
							.createQuery("select a from account a where a.username = :username",
											eu.daiad.web.domain.application.Account.class).setFirstResult(0)
							.setMaxResults(1);
			query.setParameter("username", username);

			List<eu.daiad.web.domain.application.Account> result = query.getResultList();
			if (result.size() != 0) {
				eu.daiad.web.domain.application.Account account = result.get(0);

				List<GrantedAuthority> authorities = new ArrayList<GrantedAuthority>();
				for (AccountRole r : account.getRoles()) {
					authorities.add(new SimpleGrantedAuthority(r.getRole().getName()));
				}
				user = new AuthenticatedUser(account.getKey(), account.getUsername(), account.getPassword(),
								authorities);

				user.setBirthdate(account.getBirthdate());
				user.setCountry(account.getCountry());
				user.setFirstname(account.getFirstname());
				user.setLastname(account.getLastname());
				user.setGender(account.getGender());
				user.setPostalCode(account.getPostalCode());
				user.setTimezone(account.getTimezone());

				user.setWebMode(EnumWebMode.fromInteger(account.getProfile().getWebMode()));
				user.setMobileMode(EnumMobileMode.fromInteger(account.getProfile().getMobileMode()));
				user.setUtilityMode(EnumUtilityMode.fromInteger(account.getProfile().getUtilityMode()));
			}

			return user;
		} catch (Exception ex) {
			throw ApplicationException.wrap(ex, SharedErrorCode.UNKNOWN);
		}
	}

}
