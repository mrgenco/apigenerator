package com.mrg.apigenerator.domain;

import java.util.HashMap;

public class EntityInformation {
	
	private String entityName;
	private HashMap<String, String> fields;
	/**
	 * @return the entityName
	 */
	public String getEntityName() {
		return entityName;
	}
	/**
	 * @param entityName the entityName to set
	 */
	public void setEntityName(String entityName) {
		this.entityName = entityName;
	}
	/**
	 * @return the fields
	 */
	public HashMap<String, String> getFields() {
		return fields;
	}
	/**
	 * @param fields the fields to set
	 */
	public void setFields(HashMap<String, String> fields) {
		this.fields = fields;
	}

}
