package com.tf.reader.catalogue.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ScopeType;

public interface EntitlementRepository extends MongoRepository<Entitlement, String> {

	Page<Entitlement> findByInstitutionId(String institutionId, Pageable pageable);

	List<Entitlement> findByInstitutionIdAndStatus(String institutionId, EntitlementStatus status);

	Optional<Entitlement> findByInstitutionIdAndScopeTypeAndScopeId(String institutionId, ScopeType scopeType,
			String scopeId);

	List<Entitlement> findByValidToBefore(LocalDate date);

	List<Entitlement> findByScopeTypeAndScopeId(ScopeType scopeType, String scopeId);

}
