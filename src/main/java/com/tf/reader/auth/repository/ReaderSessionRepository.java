package com.tf.reader.auth.repository;

import org.springframework.data.mongodb.repository.MongoRepository;

import com.tf.reader.auth.entity.ReaderSession;

public interface ReaderSessionRepository
		extends MongoRepository<ReaderSession, String>, ReaderSessionRepositoryCustom {

}
