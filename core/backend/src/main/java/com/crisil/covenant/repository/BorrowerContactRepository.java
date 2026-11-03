package com.crisil.covenant.repository;

import com.crisil.covenant.entity.BorrowerContact;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BorrowerContactRepository extends JpaRepository<BorrowerContact, String> {

    List<BorrowerContact> findByRemindersOnTrue();
}
