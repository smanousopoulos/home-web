package eu.daiad.web.repository.application;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;
import javax.persistence.TypedQuery;

import org.joda.time.DateTime;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.bedatadriven.jackson.datatype.jts.JtsModule;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.joda.JodaModule;

import eu.daiad.web.domain.application.AccountEntity;
import eu.daiad.web.domain.application.DataQueryEntity;
import eu.daiad.web.domain.application.FavouriteEntity;
import eu.daiad.web.domain.application.FavouriteAccountEntity;
import eu.daiad.web.domain.application.FavouriteGroupEntity;
import eu.daiad.web.domain.application.GroupEntity;
import eu.daiad.web.domain.application.GroupSegmentEntity;
import eu.daiad.web.model.error.FavouriteErrorCode;
import eu.daiad.web.model.error.GroupErrorCode;
import eu.daiad.web.model.error.SharedErrorCode;
import eu.daiad.web.model.error.UserErrorCode;
import eu.daiad.web.model.favourite.CandidateFavouriteAccountInfo;
import eu.daiad.web.model.favourite.CandidateFavouriteGroupInfo;
import eu.daiad.web.model.favourite.EnumFavouriteType;
import eu.daiad.web.model.favourite.FavouriteAccountInfo;
import eu.daiad.web.model.favourite.FavouriteGroupInfo;
import eu.daiad.web.model.favourite.FavouriteInfo;
import eu.daiad.web.model.favourite.UpsertFavouriteRequest;
import eu.daiad.web.model.group.EnumGroupType;
import eu.daiad.web.model.query.NamedDataQuery;
import eu.daiad.web.model.security.AuthenticatedUser;
import eu.daiad.web.model.security.EnumRole;
import eu.daiad.web.repository.BaseRepository;

@Repository
@Transactional("applicationTransactionManager")
public class JpaFavouriteRepository extends BaseRepository implements IFavouriteRepository {

    @PersistenceContext(unitName = "default")
    EntityManager entityManager;

    @Override
    public List<FavouriteInfo> getFavourites() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();

            // Retrieve admin's account
            TypedQuery<AccountEntity> accountQuery = entityManager.createQuery("SELECT a FROM account a WHERE a.key = :key",
                            AccountEntity.class).setFirstResult(0).setMaxResults(1);
            accountQuery.setParameter("key", user.getKey());

            AccountEntity adminAccount = accountQuery.getSingleResult();

            TypedQuery<FavouriteEntity> favouriteQuery = entityManager.createQuery(
                            "SELECT f FROM favourite f WHERE f.owner = :owner", FavouriteEntity.class).setFirstResult(0);
            favouriteQuery.setParameter("owner", adminAccount);

            List<FavouriteEntity> favourites = favouriteQuery.getResultList();
            List<FavouriteInfo> favouritesInfo = new ArrayList<FavouriteInfo>();

            for (FavouriteEntity favourite : favourites) {
                FavouriteInfo favouriteInfo = new FavouriteInfo(favourite);
                favouritesInfo.add(favouriteInfo);
            }

            return favouritesInfo;
        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }
    }

    @Override
    public FavouriteAccountInfo checkFavouriteAccount(UUID key) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();

            FavouriteAccountInfo favouriteAccountInfo = null;

            // Retrieve admin's account
            TypedQuery<AccountEntity> adminAccountQuery = entityManager.createQuery(
                            "SELECT a FROM account a WHERE a.key = :key", AccountEntity.class).setFirstResult(0)
                            .setMaxResults(1);
            adminAccountQuery.setParameter("key", user.getKey());

            AccountEntity adminAccount = adminAccountQuery.getSingleResult();

            TypedQuery<FavouriteEntity> favouriteQuery = entityManager.createQuery(
                            "SELECT f FROM favourite f WHERE f.owner = :owner", FavouriteEntity.class).setFirstResult(0);
            favouriteQuery.setParameter("owner", adminAccount);

            List<FavouriteEntity> favourites = favouriteQuery.getResultList();

            for (FavouriteEntity favourite : favourites) {
                if (favourite.getType() == EnumFavouriteType.ACCOUNT) {
                    FavouriteAccountEntity favouriteAccount = (FavouriteAccountEntity) favourite;
                    if (favouriteAccount.getAccount().getKey().equals(key)) {
                        favouriteAccountInfo = new FavouriteAccountInfo(favouriteAccount);
                    }
                }
            }

            // If the given account does not match with any existing favourite
            // we try to retrieve it
            // in order to send a CandidateFavouriteAccountInfo Object
            if (favouriteAccountInfo == null) {
                try {
                    TypedQuery<AccountEntity> accountQuery = entityManager.createQuery(
                                    "SELECT a FROM account a WHERE a.key = :key", AccountEntity.class).setFirstResult(0)
                                    .setMaxResults(1);
                    accountQuery.setParameter("key", key);

                    AccountEntity account = accountQuery.getSingleResult();

                    return new CandidateFavouriteAccountInfo(account);

                } catch (NoResultException ex) {
                    throw wrapApplicationException(ex, UserErrorCode.USER_KEY_NOT_FOUND).set("key", key);
                }
            }

            return favouriteAccountInfo;
        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }
    }

    @Override
    public FavouriteGroupInfo checkFavouriteGroup(UUID group_id) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();

            FavouriteGroupInfo favouriteGroupInfo = null;

            // Retrieve admin's account
            TypedQuery<AccountEntity> accountQuery = entityManager.createQuery("SELECT a FROM account a WHERE a.key = :key",
                            AccountEntity.class).setFirstResult(0).setMaxResults(1);
            accountQuery.setParameter("key", user.getKey());

            AccountEntity adminAccount = accountQuery.getSingleResult();

            TypedQuery<FavouriteEntity> favouriteQuery = entityManager.createQuery(
                            "SELECT f FROM favourite f WHERE f.owner = :owner", FavouriteEntity.class).setFirstResult(0);
            favouriteQuery.setParameter("owner", adminAccount);

            List<FavouriteEntity> favourites = favouriteQuery.getResultList();

            for (FavouriteEntity favourite : favourites) {
                if (favourite.getType() == EnumFavouriteType.GROUP) {
                    FavouriteGroupEntity favouriteGroup = (FavouriteGroupEntity) favourite;
                    if (favouriteGroup.getGroup().getKey().equals(group_id)) {
                        favouriteGroupInfo = new FavouriteGroupInfo(favouriteGroup);
                    }
                }
            }

            // If the given group does not match with any existing favourite we
            // try to retrieve it
            // in order to send a CandidateFavouriteGroupInfo Object
            if (favouriteGroupInfo == null) {
                try {
                    TypedQuery<GroupEntity> groupQuery = entityManager.createQuery(
                                    "SELECT g FROM group g WHERE g.key = :key", GroupEntity.class).setFirstResult(0)
                                    .setMaxResults(1);
                    groupQuery.setParameter("key", group_id);

                    GroupEntity group = groupQuery.getSingleResult();

                    return new CandidateFavouriteGroupInfo(group);

                } catch (NoResultException ex) {
                    throw wrapApplicationException(ex, GroupErrorCode.GROUP_DOES_NOT_EXIST).set("groupId", group_id);
                }
            }

            return favouriteGroupInfo;
        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }

    }

    @Override
    public void upsertFavourite(UpsertFavouriteRequest favouriteInfo) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();

            // Retrieve admin's account
            TypedQuery<AccountEntity> adminAccountQuery = entityManager.createQuery(
                            "SELECT a FROM account a WHERE a.key = :key", AccountEntity.class).setFirstResult(0)
                            .setMaxResults(1);
            adminAccountQuery.setParameter("key", user.getKey());

            AccountEntity adminAccount = adminAccountQuery.getSingleResult();

            // Checking if the Favourite's type is invalid
            if (favouriteInfo.getType() != EnumFavouriteType.GROUP
                            && favouriteInfo.getType() != EnumFavouriteType.ACCOUNT) {
                throw createApplicationException(FavouriteErrorCode.INVALID_FAVOURITE_TYPE);
            }

            if (favouriteInfo.getType() == EnumFavouriteType.ACCOUNT) {
                // checking if the Account exists at all
                AccountEntity account = null;
                try {
                    TypedQuery<AccountEntity> accountQuery = entityManager.createQuery(
                                    "SELECT a FROM account a WHERE a.key = :key", AccountEntity.class).setFirstResult(0)
                                    .setMaxResults(1);
                    accountQuery.setParameter("key", favouriteInfo.getKey());

                    account = accountQuery.getSingleResult();
                } catch (NoResultException ex) {
                    throw wrapApplicationException(ex, UserErrorCode.USER_KEY_NOT_FOUND).set("key", favouriteInfo.getKey());
                }

                // Checking if the selected account belongs to the same utility
                // as administrator
                if (account.getUtility().getId() != adminAccount.getUtility().getId()) {
                    throw createApplicationException(UserErrorCode.ACCOUNT_ACCESS_RESTRICTED);
                }

                // Checking if the account is already an admin's favourite
                TypedQuery<FavouriteEntity> favouriteQuery = entityManager.createQuery(
                                "SELECT f FROM favourite f WHERE f.owner = :owner", FavouriteEntity.class).setFirstResult(0);
                favouriteQuery.setParameter("owner", adminAccount);

                List<FavouriteEntity> favourites = favouriteQuery.getResultList();

                FavouriteAccountEntity selectedFavourite = null;
                for (FavouriteEntity favourite : favourites) {
                    if (favourite.getType() == EnumFavouriteType.ACCOUNT) {
                        FavouriteAccountEntity favouriteAccount = (FavouriteAccountEntity) favourite;
                        if (favouriteAccount.getAccount().getId() == account.getId()) {
                            selectedFavourite = favouriteAccount;
                        }
                    }
                }

                // Favourite already exists just set the label
                if (selectedFavourite != null) {
                    selectedFavourite.setLabel(favouriteInfo.getLabel());

                } else {
                    selectedFavourite = new FavouriteAccountEntity();
                    selectedFavourite.setLabel(favouriteInfo.getLabel());
                    selectedFavourite.setOwner(adminAccount);
                    selectedFavourite.setCreatedOn(new DateTime());
                    selectedFavourite.setAccount(account);
                }

                this.entityManager.persist(selectedFavourite);
            } else {
                // checking if the Group exists at all
                GroupEntity group = null;
                try {
                    TypedQuery<GroupEntity> groupQuery = entityManager.createQuery(
                                    "SELECT g FROM group g WHERE g.key = :key", GroupEntity.class).setFirstResult(0)
                                    .setMaxResults(1);
                    groupQuery.setParameter("key", favouriteInfo.getKey());

                    group = groupQuery.getSingleResult();
                } catch (NoResultException ex) {
                    throw wrapApplicationException(ex, GroupErrorCode.GROUP_DOES_NOT_EXIST).set("groupId",
                                    favouriteInfo.getKey());
                }

                // Checking if the selected group belongs to the same utility as
                // admin
                if (group.getUtility().getId() != adminAccount.getUtility().getId()) {
                    throw createApplicationException(GroupErrorCode.GROUP_ACCESS_RESTRICTED);
                }

                // Checking if the group is already an admin's favourite
                TypedQuery<FavouriteEntity> favouriteQuery = entityManager.createQuery(
                                "SELECT f FROM favourite f WHERE f.owner = :owner", FavouriteEntity.class).setFirstResult(0);
                favouriteQuery.setParameter("owner", adminAccount);

                List<FavouriteEntity> favourites = favouriteQuery.getResultList();

                FavouriteGroupEntity selectedFavourite = null;
                for (FavouriteEntity favourite : favourites) {
                    if (favourite.getType() == EnumFavouriteType.GROUP) {
                        FavouriteGroupEntity favouriteGroup = (FavouriteGroupEntity) favourite;
                        if (favouriteGroup.getGroup().getId() == group.getId()) {
                            selectedFavourite = favouriteGroup;
                        }
                    }
                }

                // Favourite already exists just set the label
                if (selectedFavourite != null) {
                    selectedFavourite.setLabel(favouriteInfo.getLabel());

                } else {
                    selectedFavourite = new FavouriteGroupEntity();
                    selectedFavourite.setLabel(favouriteInfo.getLabel());
                    selectedFavourite.setOwner(adminAccount);
                    selectedFavourite.setCreatedOn(new DateTime());
                    selectedFavourite.setGroup(group);
                }

                this.entityManager.persist(selectedFavourite);
            }

        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }

    }

    @Override
    public void deleteFavourite(UUID favourite_id) {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            AuthenticatedUser user = (AuthenticatedUser) auth.getPrincipal();

            if (!user.hasRole(EnumRole.ROLE_UTILITY_ADMIN, EnumRole.ROLE_SYSTEM_ADMIN)) {
                throw createApplicationException(SharedErrorCode.AUTHORIZATION);
            }

            FavouriteEntity favourite = null;
            // Check if favourite exists
            try {
                TypedQuery<FavouriteEntity> favouriteQuery = entityManager.createQuery(
                                "select f from favourite f where f.key = :favourite_id", FavouriteEntity.class)
                                .setFirstResult(0).setMaxResults(1);
                favouriteQuery.setParameter("favourite_id", favourite_id);
                favourite = favouriteQuery.getSingleResult();

                // Check that admin is the owner of the group
                // Get admin's account
                TypedQuery<eu.daiad.web.domain.application.AccountEntity> adminAccountQuery = entityManager.createQuery(
                                "select a from account a where a.id = :adminId",
                                eu.daiad.web.domain.application.AccountEntity.class).setFirstResult(0).setMaxResults(1);
                adminAccountQuery.setParameter("adminId", user.getId());
                AccountEntity adminAccount = adminAccountQuery.getSingleResult();

                if (favourite.getOwner() == adminAccount) {
                    this.entityManager.remove(favourite);

                } else {
                    throw createApplicationException(FavouriteErrorCode.FAVOURITE_ACCESS_RESTRICTED).set("favouriteId",
                                    favourite_id);
                }

            } catch (NoResultException ex) {
                throw wrapApplicationException(ex, FavouriteErrorCode.FAVOURITE_DOES_NOT_EXIST).set("favouriteId",
                                favourite_id);
            }

        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }
    }

    @Override
    public void addUserFavorite(UUID ownerKey, UUID userKey) {
        if (isUserFavorite(ownerKey, userKey)) {
            return;
        }

        TypedQuery<eu.daiad.web.domain.application.AccountEntity> query = entityManager.createQuery(
                        "select a from account a where a.key = :key", eu.daiad.web.domain.application.AccountEntity.class);

        query.setParameter("key", ownerKey);

        eu.daiad.web.domain.application.AccountEntity owner = query.getSingleResult();

        query.setParameter("key", userKey);

        eu.daiad.web.domain.application.AccountEntity account = query.getSingleResult();

        eu.daiad.web.domain.application.FavouriteAccountEntity favorite = new eu.daiad.web.domain.application.FavouriteAccountEntity();
        favorite.setAccount(account);
        favorite.setCreatedOn(new DateTime());
        favorite.setOwner(owner);
        favorite.setLabel(account.getFullname());

        entityManager.persist(favorite);
    }

    @Override
    public void deleteUserFavorite(UUID ownerKey, UUID userKey) {
        TypedQuery<eu.daiad.web.domain.application.FavouriteAccountEntity> query = entityManager.createQuery(
                        "SELECT f FROM favourite_account f "
                                        + "WHERE f.owner.key = :ownerKey and f.account.key = :userKey",
                        eu.daiad.web.domain.application.FavouriteAccountEntity.class).setFirstResult(0).setMaxResults(1);

        query.setParameter("ownerKey", ownerKey);
        query.setParameter("userKey", userKey);

        List<FavouriteAccountEntity> favorites = query.getResultList();

        if (!favorites.isEmpty()) {
            entityManager.remove(favorites.get(0));
        }
    }

    @Override
    public boolean isUserFavorite(UUID ownerKey, UUID userKey) {
        TypedQuery<eu.daiad.web.domain.application.FavouriteAccountEntity> query = entityManager.createQuery(
                        "SELECT f FROM favourite_account f "
                                        + "WHERE f.owner.key = :ownerKey and f.account.key = :userKey",
                        eu.daiad.web.domain.application.FavouriteAccountEntity.class).setFirstResult(0).setMaxResults(1);

        query.setParameter("ownerKey", ownerKey);
        query.setParameter("userKey", userKey);

        return (!query.getResultList().isEmpty());
    }

    @Override
    public void addGroupFavorite(UUID ownerKey, UUID groupKey) {
        if (isGroupFavorite(ownerKey, groupKey)) {
            return;
        }

        TypedQuery<eu.daiad.web.domain.application.AccountEntity> accountQuery = entityManager.createQuery(
                        "select a from account a where a.key = :key", eu.daiad.web.domain.application.AccountEntity.class);

        accountQuery.setParameter("key", ownerKey);

        eu.daiad.web.domain.application.AccountEntity owner = accountQuery.getSingleResult();

        TypedQuery<eu.daiad.web.domain.application.GroupEntity> groupQuery = entityManager.createQuery(
                        "select g from group g where g.key = :key", eu.daiad.web.domain.application.GroupEntity.class);

        groupQuery.setParameter("key", groupKey);

        eu.daiad.web.domain.application.GroupEntity group = groupQuery.getSingleResult();

        eu.daiad.web.domain.application.FavouriteGroupEntity favorite = new eu.daiad.web.domain.application.FavouriteGroupEntity();
        favorite.setGroup(group);
        favorite.setCreatedOn(new DateTime());
        favorite.setOwner(owner);
        if (group.getType() == EnumGroupType.SEGMENT) {
            favorite.setLabel(((GroupSegmentEntity) group).getCluster().getName() + " - " + group.getName());
        } else {
            favorite.setLabel(group.getName());
        }

        entityManager.persist(favorite);
    }

    @Override
    public void deleteGroupFavorite(UUID ownerKey, UUID groupKey) {
        TypedQuery<eu.daiad.web.domain.application.FavouriteGroupEntity> query = entityManager.createQuery(
                        "SELECT f FROM favourite_group f "
                                        + "WHERE f.owner.key = :ownerKey and f.group.key = :groupKey",
                        eu.daiad.web.domain.application.FavouriteGroupEntity.class).setFirstResult(0).setMaxResults(1);

        query.setParameter("ownerKey", ownerKey);
        query.setParameter("groupKey", groupKey);

        List<FavouriteGroupEntity> favorites = query.getResultList();

        if (!favorites.isEmpty()) {
            entityManager.remove(favorites.get(0));
        }
    }

    @Override
    public boolean isGroupFavorite(UUID ownerKey, UUID groupKey) {
        TypedQuery<eu.daiad.web.domain.application.FavouriteGroupEntity> query = entityManager.createQuery(
                        "SELECT f FROM favourite_group f "
                                        + "WHERE f.owner.key = :ownerKey and f.group.key = :groupKey",
                        eu.daiad.web.domain.application.FavouriteGroupEntity.class).setFirstResult(0).setMaxResults(1);

        query.setParameter("ownerKey", ownerKey);
        query.setParameter("groupKey", groupKey);

        return (!query.getResultList().isEmpty());
    }

    @Override
    public void insertFavouriteQuery(NamedDataQuery namedDataQuery, AccountEntity account) {



        try {
            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            // Add additional modules to JSON parser
            builder.modules(new JodaModule(), new JtsModule());
            ObjectMapper objectMapper = builder.build();

            TypedQuery<eu.daiad.web.domain.application.DataQueryEntity> queryCheck = entityManager.createQuery(
                            "SELECT d FROM data_query d WHERE d.owner.id = :accountId and d.name = :name",
                            eu.daiad.web.domain.application.DataQueryEntity.class).setFirstResult(0).setMaxResults(1);

            queryCheck.setParameter("accountId", account.getId());
            queryCheck.setParameter("name", namedDataQuery.getTitle());

            List<DataQueryEntity> duplicate = queryCheck.getResultList();

            String finalTitle;
            if(duplicate.size() > 0){
                finalTitle = namedDataQuery.getTitle() + " (duplicate title) " + duplicate.get(0).getId();
            } else {
                finalTitle = namedDataQuery.getTitle();
            }

            DataQueryEntity dataQueryEntity = new DataQueryEntity();

            dataQueryEntity.setType(namedDataQuery.getType());
            dataQueryEntity.setName(finalTitle);
            dataQueryEntity.setTags(namedDataQuery.getTags());
            dataQueryEntity.setReportName(namedDataQuery.getReportName());
            dataQueryEntity.setLevel(namedDataQuery.getLevel());
            dataQueryEntity.setField(namedDataQuery.getField());
            dataQueryEntity.setQuery(objectMapper.writeValueAsString(namedDataQuery.getQuery()));
            dataQueryEntity.setOwner(account);
            dataQueryEntity.setUpdatedOn(DateTime.now());

            this.entityManager.persist(dataQueryEntity);

        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }
    }

    @Override
    public void updateFavouriteQuery(NamedDataQuery namedDataQuery, AccountEntity account) {

        try {

            Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
            // Add additional modules to JSON parser
            builder.modules(new JodaModule(), new JtsModule());
            ObjectMapper objectMapper = builder.build();

            TypedQuery<eu.daiad.web.domain.application.DataQueryEntity> queryCheck = entityManager.createQuery(
                            "SELECT d FROM data_query d WHERE d.owner.id = :accountId and d.name = :name",
                            eu.daiad.web.domain.application.DataQueryEntity.class).setFirstResult(0).setMaxResults(1);

            queryCheck.setParameter("accountId", account.getId());
            queryCheck.setParameter("name", namedDataQuery.getTitle());

            List<DataQueryEntity> duplicate = queryCheck.getResultList();

            String finalTitle;
            if(duplicate.size() > 0){
                finalTitle = namedDataQuery.getTitle() + " (duplicate title) "+ duplicate.get(0).getId();
            } else {
                finalTitle = namedDataQuery.getTitle();
            }

            TypedQuery<eu.daiad.web.domain.application.DataQueryEntity> query = entityManager.createQuery(
                            "SELECT d FROM data_query d WHERE d.owner.id = :accountId and d.id = :id",
                            eu.daiad.web.domain.application.DataQueryEntity.class).setFirstResult(0).setMaxResults(1);

            query.setParameter("id", namedDataQuery.getId());
            query.setParameter("accountId", account.getId());

            DataQueryEntity dataQueryEntity = query.getSingleResult();

            dataQueryEntity.setName(finalTitle);
            dataQueryEntity.setQuery(objectMapper.writeValueAsString(namedDataQuery.getQuery()));
            dataQueryEntity.setTags(namedDataQuery.getTags());
            dataQueryEntity.setReportName(namedDataQuery.getReportName());
            dataQueryEntity.setLevel(namedDataQuery.getLevel());
            dataQueryEntity.setField(namedDataQuery.getField());

            this.entityManager.persist(dataQueryEntity);

        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }
    }

    @Override
    public void deleteFavouriteQuery(NamedDataQuery namedDataQuery, AccountEntity account) {

        try {
            TypedQuery<eu.daiad.web.domain.application.DataQueryEntity> query = entityManager.createQuery(
                            "SELECT d FROM data_query d WHERE d.owner.id = :accountId and d.id = :id",
                            eu.daiad.web.domain.application.DataQueryEntity.class).setFirstResult(0).setMaxResults(1);

            query.setParameter("id", namedDataQuery.getId());
            query.setParameter("accountId", account.getId());

            DataQueryEntity dataQueryEntity = query.getSingleResult();

            this.entityManager.remove(dataQueryEntity);

        } catch (Exception ex) {
            throw wrapApplicationException(ex, SharedErrorCode.UNKNOWN);
        }
    }

    @Override
    public List<NamedDataQuery> getFavouriteQueriesForOwner(int accountId)
            throws JsonMappingException, JsonParseException, IOException{

        List<NamedDataQuery> namedDataQueries = new ArrayList<>();

        TypedQuery<eu.daiad.web.domain.application.DataQueryEntity> query = entityManager.createQuery(
                        "SELECT d FROM data_query d WHERE d.owner.id = :accountId order by d.updatedOn desc",
                        eu.daiad.web.domain.application.DataQueryEntity.class);
        query.setParameter("accountId", accountId);

        for(DataQueryEntity queryEntity : query.getResultList()){

            NamedDataQuery namedDataQuery = new NamedDataQuery();

            namedDataQuery.setId(queryEntity.getId());
            namedDataQuery.setType(queryEntity.getType());
            namedDataQuery.setTitle(queryEntity.getName());
            namedDataQuery.setTags(queryEntity.getTags());
            namedDataQuery.setReportName(queryEntity.getReportName());
            namedDataQuery.setLevel(queryEntity.getLevel());
            namedDataQuery.setField(queryEntity.getField());
            namedDataQuery.setQuery(queryEntity.toDataQuery());
            namedDataQuery.setCreatedOn(queryEntity.getUpdatedOn());

            namedDataQueries.add(namedDataQuery);
        }

        return namedDataQueries;
    }

    @Override
    public List<NamedDataQuery> getAllFavouriteQueries(){
        throw createApplicationException(SharedErrorCode.NOT_IMPLEMENTED);

//        TypedQuery<eu.daiad.web.domain.application.DataQueryEntity> query = entityManager.createQuery(
//                        "SELECT d FROM data_query d",
//                        eu.daiad.web.domain.application.DataQueryEntity.class);
//
//        List<NamedDataQuery> namedDataQueries = new ArrayList<>();
//        try {
//            for(DataQueryEntity queryEntity : query.getResultList()){
//
//                    NamedDataQuery namedDataQuery = new NamedDataQuery();
//
//                    namedDataQuery.setId(queryEntity.getId());
//                    namedDataQuery.setType(queryEntity.getType());
//                    namedDataQuery.setTitle(queryEntity.getName());
//                    namedDataQuery.setTags(queryEntity.getTags());
//                    namedDataQuery.setQuery(queryEntity.toDataQuery());
//                    namedDataQuery.setCreatedOn(queryEntity.getUpdatedOn());
//
//                    namedDataQueries.add(namedDataQuery);
//
//            }
//        } catch (JsonMappingException ex) {
//            Logger.getLogger(JpaFavouriteRepository.class.getName()).log(Level.SEVERE, null, ex);
//        } catch (IOException ex) {
//            Logger.getLogger(JpaFavouriteRepository.class.getName()).log(Level.SEVERE, null, ex);
//        }
//        return namedDataQueries;
    }
}
