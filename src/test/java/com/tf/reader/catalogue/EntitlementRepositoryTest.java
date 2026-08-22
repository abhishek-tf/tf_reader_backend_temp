package com.tf.reader.catalogue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.ContainerisedInfrastructure;
import com.tf.reader.catalogue.entity.Entitlement;
import com.tf.reader.catalogue.entity.EntitlementStatus;
import com.tf.reader.catalogue.entity.ScopeType;
import com.tf.reader.catalogue.repository.EntitlementRepository;

@SpringBootTest(properties = "tnf.auth.jwt.secret=" + ContainerisedInfrastructure.JWT_SECRET)
class EntitlementRepositoryTest extends ContainerisedInfrastructure {

	@Autowired
	private EntitlementRepository entitlementRepository;

	private Entitlement newEntitlement(String institutionId, ScopeType scopeType, String scopeId) {
		Entitlement entitlement = new Entitlement();
		entitlement.setInstitutionId(institutionId);
		entitlement.setScopeType(scopeType);
		entitlement.setScopeId(scopeId);
		entitlement.setStatus(EntitlementStatus.ACTIVE);
		return entitlement;
	}

	@Test
	void savesAndReadsBackAnEntitlement() {
		Entitlement saved = entitlementRepository.save(
				newEntitlement("inst_7f3", ScopeType.COLLECTION, "col_law2024"));
		Entitlement found = entitlementRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getInstitutionId()).isEqualTo("inst_7f3");
		assertThat(found.getScopeType()).isEqualTo(ScopeType.COLLECTION);
	}

	@Test
	void rejectsADuplicateEntitlementForTheSameInstitutionScopeAndScopeId() {
		entitlementRepository.save(newEntitlement("inst_dupe", ScopeType.PUBLISHER, "pub_rtlg"));

		assertThatThrownBy(() -> entitlementRepository.save(newEntitlement("inst_dupe", ScopeType.PUBLISHER, "pub_rtlg")))
				.isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void findsEveryEntitlementForAScopeRegardlessOfStatus() {
		entitlementRepository.save(newEntitlement("inst_a", ScopeType.COLLECTION, "col_1"));
		Entitlement suspended = newEntitlement("inst_b", ScopeType.COLLECTION, "col_1");
		suspended.setStatus(EntitlementStatus.SUSPENDED);
		entitlementRepository.save(suspended);
		entitlementRepository.save(newEntitlement("inst_a", ScopeType.COLLECTION, "col_2"));

		List<Entitlement> found = entitlementRepository.findByScopeTypeAndScopeId(ScopeType.COLLECTION, "col_1");

		assertThat(found).extracting(Entitlement::getInstitutionId).containsExactlyInAnyOrder("inst_a", "inst_b");
	}

	@Test
	void aScopeWithNoEntitlementsReturnsAnEmptyList() {
		List<Entitlement> found = entitlementRepository.findByScopeTypeAndScopeId(ScopeType.ITEM, "item_none");

		assertThat(found).isEmpty();
	}

}
