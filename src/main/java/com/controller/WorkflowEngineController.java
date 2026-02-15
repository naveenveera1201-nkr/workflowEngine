package com.controller;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ProcessFlowEngine;
import com.configs.registries.process.ProcessRegistry;
import com.exceptions.ProcessFlowException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.models.Process;
import com.resource.WorkflowEngineResource;

import lombok.extern.slf4j.Slf4j;

@RestController
@Slf4j
public class WorkflowEngineController implements WorkflowEngineResource {

	@Autowired
	private ProcessFlowEngine processFlowEngine;
	
	@Override
	public String process(@RequestParam("data") String data, @RequestParam("code") String code) {

		log.info("ProcessWorkflowController method called");

		try {

			ObjectMapper mapper = new ObjectMapper();
			String processCode = processFlowEngine.getProcessRegistry().get(code).getCode();
			Map<String, Object> dataMap = mapper.readValue(data, new TypeReference<Map<String, Object>>() {
			});

			if (code.equalsIgnoreCase(processCode)) {

				processFlowEngine.createProcessInstance(processCode, "naveen", dataMap);

			} else if (code.equalsIgnoreCase(processCode)) {

			} else if (code.equalsIgnoreCase(processCode)) {

			}
		} catch (ProcessFlowException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return "";
	}

}
