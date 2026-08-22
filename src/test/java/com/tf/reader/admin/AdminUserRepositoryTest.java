package com.tf.reader.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;

import com.tf.reader.TestcontainersConfiguration;
import com.tf.reader.admin.entity.AdminRole;
import com.tf.reader.admin.entity.AdminStatus;
import com.tf.reader.admin.entity.AdminUser;
import com.tf.reader.admin.repository.AdminUserRepository;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AdminUserRepositoryTest {

	@Autowired
	private AdminUserRepository adminUserRepository;

	private AdminUser newAdminUser(String email) {
		AdminUser adminUser = new AdminUser();
		adminUser.setEmail(email);
		adminUser.setName("Catalogue Ops");
		adminUser.setPasswordHash("$2a$10$hash");
		adminUser.setRole(AdminRole.SUPER_ADMIN);
		adminUser.setStatus(AdminStatus.ACTIVE);
		return adminUser;
	}

	@Test
	void savesAndReadsBackAnAdminUser() {
		AdminUser saved = adminUserRepository.save(newAdminUser("ops@tandf.example"));
		AdminUser found = adminUserRepository.findById(saved.getId()).orElseThrow();

		assertThat(found.getEmail()).isEqualTo("ops@tandf.example");
		assertThat(found.getRole()).isEqualTo(AdminRole.SUPER_ADMIN);
	}

	@Test
	void rejectsASecondAdminUserWithTheSameEmail() {
		adminUserRepository.save(newAdminUser("dupe@tandf.example"));

		assertThatThrownBy(() -> adminUserRepository.save(newAdminUser("dupe@tandf.example")))
				.isInstanceOf(DuplicateKeyException.class);
	}

}
