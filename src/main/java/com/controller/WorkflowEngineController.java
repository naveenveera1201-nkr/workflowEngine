package com.controller;

import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ProcessFlowEngine;
import com.exceptions.ProcessFlowException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
		String result = "";

		try {

			ObjectMapper mapper = new ObjectMapper();
			
			String processCode = processFlowEngine.getProcessRegistry().get(code).getCode();
			
			Map<String, Object> dataMap = mapper.readValue(data, new TypeReference<Map<String, Object>>() {
			});

			if ("p100".equalsIgnoreCase(processCode) && Objects.isNull(dataMap.get("action"))) {
				result = processFlowEngine.createProcessInstance(processCode, "naveen", dataMap);
			} else if ("p100".equalsIgnoreCase(processCode)) {
				result = processFlowEngine.performAction(dataMap.get("id").toString(), dataMap.get("action").toString(),
						"naveen", "naveen");
			} else {
				processFlowEngine.getProcessInstance(dataMap.get("id").toString());
			}
		} catch (ProcessFlowException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (JsonProcessingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		return result;
	}

}
