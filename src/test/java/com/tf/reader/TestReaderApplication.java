package com.tf.reader;

import org.springframework.boot.SpringApplication;

public class TestReaderApplication {

	public static void main(String[] args) {
		SpringApplication.from(ReaderApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
