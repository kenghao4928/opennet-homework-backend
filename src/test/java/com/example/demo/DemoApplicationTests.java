package com.example.demo;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("需要實際的 Redis/MySQL/RocketMQ 服務；單元測試以 Service/Controller 層覆蓋")
@SpringBootTest
class DemoApplicationTests {

	@Test
	void contextLoads() {
	}

}
