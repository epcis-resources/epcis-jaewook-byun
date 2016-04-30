package org.oliot.epcis.service.query.mongodb;

import java.io.StringWriter;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.bind.JAXB;
import javax.xml.bind.JAXBElement;
import javax.xml.datatype.XMLGregorianCalendar;
import javax.xml.namespace.QName;

import org.apache.log4j.Level;
import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.json.JSONArray;
import org.json.JSONObject;
import org.oliot.epcis.configuration.Configuration;
import org.oliot.epcis.security.OAuthUtil;
import org.oliot.epcis.serde.mongodb.AggregationEventReadConverter;
import org.oliot.epcis.serde.mongodb.MasterDataReadConverter;
import org.oliot.epcis.serde.mongodb.ObjectEventReadConverter;
import org.oliot.epcis.serde.mongodb.QuantityEventReadConverter;
import org.oliot.epcis.serde.mongodb.TransactionEventReadConverter;
import org.oliot.epcis.serde.mongodb.TransformationEventReadConverter;
import org.oliot.model.epcis.AggregationEventType;
import org.oliot.model.epcis.AttributeType;
import org.oliot.model.epcis.EPCISQueryBodyType;
import org.oliot.model.epcis.EPCISQueryDocumentType;
import org.oliot.model.epcis.EventListType;
import org.oliot.model.epcis.InvalidURIException;
import org.oliot.model.epcis.ObjectEventType;
import org.oliot.model.epcis.QuantityEventType;
import org.oliot.model.epcis.QueryParam;
import org.oliot.model.epcis.QueryParameterException;
import org.oliot.model.epcis.QueryParams;
import org.oliot.model.epcis.QueryResults;
import org.oliot.model.epcis.QueryResultsBody;
import org.oliot.model.epcis.QueryTooLargeException;
import org.oliot.model.epcis.SubscribeNotPermittedException;
import org.oliot.model.epcis.SubscriptionControlsException;
import org.oliot.model.epcis.TransactionEventType;
import org.oliot.model.epcis.TransformationEventType;
import org.oliot.model.epcis.VocabularyElementType;
import org.oliot.model.epcis.VocabularyListType;
import org.oliot.model.epcis.VocabularyType;

import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;

import static org.oliot.epcis.service.query.mongodb.MongoQueryUtil.*;

/**
 * Copyright (C) 2014-2016 Jaewook Byun
 *
 * This project is part of Oliot (oliot.org), pursuing the implementation of
 * Electronic Product Code Information Service(EPCIS) v1.1 specification in
 * EPCglobal.
 * [http://www.gs1.org/gsmp/kc/epcglobal/epcis/epcis_1_1-standard-20140520.pdf]
 * 
 *
 * @author Jaewook Byun, Ph.D student ` Korea Advanced Institute of Science and
 *         Technology (KAIST)
 * 
 *         Real-time Embedded System Laboratory(RESL)
 * 
 *         bjw0829@kaist.ac.kr, bjw0829@gmail.com
 */

public class MongoQueryService {

	@SuppressWarnings({ "unchecked", "rawtypes" })
	public String pollEventQuery(String queryName, String eventType, String GE_eventTime, String LT_eventTime,
			String GE_recordTime, String LT_recordTime, String EQ_action, String EQ_bizStep, String EQ_disposition,
			String EQ_readPoint, String WD_readPoint, String EQ_bizLocation, String WD_bizLocation,
			String EQ_transformationID, String MATCH_epc, String MATCH_parentID, String MATCH_inputEPC,
			String MATCH_outputEPC, String MATCH_anyEPC, String MATCH_epcClass, String MATCH_inputEPCClass,
			String MATCH_outputEPCClass, String MATCH_anyEPCClass, Integer EQ_quantity, Integer GT_quantity,
			Integer GE_quantity, Integer LT_quantity, Integer LE_quantity, String EQ_eventID,
			Boolean EXISTS_errorDeclaration, String GE_errorDeclarationTime, String LT_errorDeclarationTime,
			String EQ_errorReason, String EQ_correctiveEventID, String orderBy, String orderDirection,
			Integer eventCountLimit, Integer maxEventCount, String format, String userID, List<String> friendList,
			Map<String, String> paramMap) {

		// M27 - query params' constraint
		// M39 - query params' constraint
		String reason = checkConstraintSimpleEventQuery(queryName, eventType, GE_eventTime, LT_eventTime, GE_recordTime,
				LT_recordTime, EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint, EQ_bizLocation,
				WD_bizLocation, EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC, MATCH_outputEPC,
				MATCH_anyEPC, MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass, MATCH_anyEPCClass, EQ_quantity,
				GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID, EXISTS_errorDeclaration,
				GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason, EQ_correctiveEventID, orderBy,
				orderDirection, eventCountLimit, maxEventCount, paramMap);

		if (reason != null) {
			return makeErrorResult(reason, QueryParameterException.class);
		}

		// Make Base Result Document
		EPCISQueryDocumentType epcisQueryDocumentType = null;
		JSONObject retJSON = new JSONObject();

		if (format == null || format.equals("XML")) {
			epcisQueryDocumentType = makeBaseResultDocument(queryName);
		} else if (format.equals("JSON")) {
			// Do Nothing
		} else {
			return makeErrorResult("format param should be one of XML or JSON", QueryParameterException.class);
		}

		// Prepare container which query results are included
		// eventObjects : Container which all the query results (events) will be
		// contained
		List<Object> eventObjects = null;
		if (format == null || format.equals("XML")) {
			eventObjects = epcisQueryDocumentType.getEPCISBody().getQueryResults().getResultsBody().getEventList()
					.getObjectEventOrAggregationEventOrQuantityEvent();
		} else {
			// foramt == JSON -> Do Nothing
		}
		// To be filtered by eventType
		boolean toGetAggregationEvent = true;
		boolean toGetObjectEvent = true;
		boolean toGetQuantityEvent = true;
		boolean toGetTransactionEvent = true;
		boolean toGetTransformationEvent = true;

		/**
		 * EventType : If specified, the result will only include events whose
		 * type matches one of the types specified in the parameter value. Each
		 * element of the parameter value may be one of the following strings:
		 * ObjectEvent, AggregationEvent, QuantityEvent, TransactionEvent, or
		 * TransformationEvent. An element of the parameter value may also be
		 * the name of an extension event type. If omitted, all event types will
		 * be considered for inclusion in the result.
		 */
		if (eventType != null) {
			toGetAggregationEvent = false;
			toGetObjectEvent = false;
			toGetQuantityEvent = false;
			toGetTransactionEvent = false;
			toGetTransformationEvent = false;

			String[] eventTypeArray = eventType.split(",");

			for (int i = 0; i < eventTypeArray.length; i++) {
				String eventTypeString = eventTypeArray[i];

				if (eventTypeString != null)
					eventTypeString = eventTypeString.trim();

				if (eventTypeString.equals("AggregationEvent"))
					toGetAggregationEvent = true;
				else if (eventTypeString.equals("ObjectEvent"))
					toGetObjectEvent = true;
				else if (eventTypeString.equals("QuantityEvent"))
					toGetQuantityEvent = true;
				else if (eventTypeString.equals("TransactionEvent"))
					toGetTransactionEvent = true;
				else if (eventTypeString.equals("TransformationEvent"))
					toGetTransformationEvent = true;
			}
		}

		if (toGetAggregationEvent == true) {
			// Aggregation Event Collection
			MongoCollection<BsonDocument> collection = Configuration.mongoDatabase.getCollection("AggregationEvent",
					BsonDocument.class);

			// Queries
			BsonArray queryList = makeQueryObjects("AggregationEvent", eventType, GE_eventTime, LT_eventTime,
					GE_recordTime, LT_recordTime, EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint,
					EQ_bizLocation, WD_bizLocation, EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC,
					MATCH_outputEPC, MATCH_anyEPC, MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass,
					MATCH_anyEPCClass, EQ_quantity, GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID,
					EXISTS_errorDeclaration, GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason,
					EQ_correctiveEventID, orderBy, orderDirection, eventCountLimit, maxEventCount, format, userID,
					friendList, paramMap);

			// Merge All the queries with $and
			BsonDocument baseQuery = new BsonDocument();
			FindIterable<BsonDocument> cursor;
			if (queryList.isEmpty() == false) {
				BsonArray aggreQueryList = new BsonArray();
				for (int i = 0; i < queryList.size(); i++) {
					aggreQueryList.add(queryList.get(i));
				}
				baseQuery.put("$and", aggreQueryList);
				// Query
				cursor = collection.find(baseQuery);
			} else {
				cursor = collection.find();
			}
			// Sort and Limit
			cursor = makeSortedLimitedCursor(cursor, orderBy, orderDirection, eventCountLimit);

			JSONArray aggrJSONArray = new JSONArray();

			MongoCursor<BsonDocument> slCursor = cursor.iterator();
			while (slCursor.hasNext()) {
				BsonDocument dbObject = slCursor.next();

				if (OAuthUtil.isAccessible(userID, friendList, dbObject) == false) {
					continue;
				}

				if (format == null || format.equals("XML")) {
					AggregationEventReadConverter con = new AggregationEventReadConverter();
					JAXBElement element = new JAXBElement(new QName("AggregationEvent"), AggregationEventType.class,
							con.convert(dbObject));
					eventObjects.add(element);
				} else {
					dbObject.remove("_id");
					aggrJSONArray.put(dbObject);
				}
			}
			if (aggrJSONArray.length() > 0) {
				retJSON.put("AggregationEvent", aggrJSONArray);
			}
		}

		// For Each Event Type!
		if (toGetObjectEvent == true) {

			MongoCollection<BsonDocument> collection = Configuration.mongoDatabase.getCollection("ObjectEvent",
					BsonDocument.class);
			// Queries
			BsonArray queryList = makeQueryObjects("ObjectEvent", eventType, GE_eventTime, LT_eventTime, GE_recordTime,
					LT_recordTime, EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint, EQ_bizLocation,
					WD_bizLocation, EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC, MATCH_outputEPC,
					MATCH_anyEPC, MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass, MATCH_anyEPCClass,
					EQ_quantity, GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID,
					EXISTS_errorDeclaration, GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason,
					EQ_correctiveEventID, orderBy, orderDirection, eventCountLimit, maxEventCount, format, userID,
					friendList, paramMap);

			// Merge All the queries with $and
			BsonDocument baseQuery = new BsonDocument();
			FindIterable<BsonDocument> cursor;
			if (queryList.isEmpty() == false) {
				BsonArray aggreQueryList = new BsonArray();
				for (int i = 0; i < queryList.size(); i++) {
					aggreQueryList.add(queryList.get(i));
				}
				baseQuery.put("$and", aggreQueryList);
				// Query
				cursor = collection.find(baseQuery);
			} else {
				cursor = collection.find();
			}
			// Sort and Limit
			cursor = makeSortedLimitedCursor(cursor, orderBy, orderDirection, eventCountLimit);

			JSONArray objJSONArray = new JSONArray();

			MongoCursor<BsonDocument> slCursor = cursor.iterator();

			while (slCursor.hasNext()) {
				BsonDocument dbObject = slCursor.next();

				if (OAuthUtil.isAccessible(userID, friendList, dbObject) == false) {
					continue;
				}

				if (format == null || format.equals("XML")) {
					ObjectEventReadConverter con = new ObjectEventReadConverter();
					JAXBElement element = new JAXBElement(new QName("ObjectEvent"), ObjectEventType.class,
							con.convert(dbObject));
					eventObjects.add(element);
				} else {
					dbObject.remove("_id");
					objJSONArray.put(dbObject);
				}
			}
			if (objJSONArray.length() > 0) {
				retJSON.put("ObjectEvent", objJSONArray);
			}
		}
		if (toGetQuantityEvent == true) {

			MongoCollection<BsonDocument> collection = Configuration.mongoDatabase.getCollection("QuantityEvent",
					BsonDocument.class);
			// Queries
			BsonArray queryList = makeQueryObjects("QuantityEvent", eventType, GE_eventTime, LT_eventTime,
					GE_recordTime, LT_recordTime, EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint,
					EQ_bizLocation, WD_bizLocation, EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC,
					MATCH_outputEPC, MATCH_anyEPC, MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass,
					MATCH_anyEPCClass, EQ_quantity, GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID,
					EXISTS_errorDeclaration, GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason,
					EQ_correctiveEventID, orderBy, orderDirection, eventCountLimit, maxEventCount, format, userID,
					friendList, paramMap);

			// Merge All the queries with $and
			BsonDocument baseQuery = new BsonDocument();
			FindIterable<BsonDocument> cursor;
			if (queryList.isEmpty() == false) {
				BsonArray aggreQueryList = new BsonArray();
				for (int i = 0; i < queryList.size(); i++) {
					aggreQueryList.add(queryList.get(i));
				}
				baseQuery.put("$and", aggreQueryList);
				// Query
				cursor = collection.find(baseQuery);
			} else {
				cursor = collection.find();
			}
			// Sort and Limit
			cursor = makeSortedLimitedCursor(cursor, orderBy, orderDirection, eventCountLimit);

			JSONArray qntJSONArray = new JSONArray();

			MongoCursor<BsonDocument> slCursor = cursor.iterator();
			while (slCursor.hasNext()) {
				BsonDocument dbObject = slCursor.next();

				if (OAuthUtil.isAccessible(userID, friendList, dbObject) == false) {
					continue;
				}

				if (format == null || format.equals("XML")) {
					QuantityEventReadConverter con = new QuantityEventReadConverter();
					JAXBElement element = new JAXBElement(new QName("QuantityEvent"), QuantityEventType.class,
							con.convert(dbObject));
					eventObjects.add(element);
				} else {
					dbObject.remove("_id");
					qntJSONArray.put(dbObject);
				}
			}
			if (qntJSONArray.length() > 0) {
				retJSON.put("QuantityEvent", qntJSONArray);
			}
		}
		if (toGetTransactionEvent == true) {

			MongoCollection<BsonDocument> collection = Configuration.mongoDatabase.getCollection("TransactionEvent",
					BsonDocument.class);
			// Queries
			BsonArray queryList = makeQueryObjects("TransactionEvent", eventType, GE_eventTime, LT_eventTime,
					GE_recordTime, LT_recordTime, EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint,
					EQ_bizLocation, WD_bizLocation, EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC,
					MATCH_outputEPC, MATCH_anyEPC, MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass,
					MATCH_anyEPCClass, EQ_quantity, GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID,
					EXISTS_errorDeclaration, GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason,
					EQ_correctiveEventID, orderBy, orderDirection, eventCountLimit, maxEventCount, format, userID,
					friendList, paramMap);

			// Merge All the queries with $and
			BsonDocument baseQuery = new BsonDocument();
			FindIterable<BsonDocument> cursor;
			if (queryList.isEmpty() == false) {
				BsonArray aggreQueryList = new BsonArray();
				for (int i = 0; i < queryList.size(); i++) {
					aggreQueryList.add(queryList.get(i));
				}
				baseQuery.put("$and", aggreQueryList);
				// Query
				cursor = collection.find(baseQuery);
			} else {
				cursor = collection.find();
			}
			// Sort and Limit
			cursor = makeSortedLimitedCursor(cursor, orderBy, orderDirection, eventCountLimit);

			JSONArray transactionJSONArray = new JSONArray();

			MongoCursor<BsonDocument> slCursor = cursor.iterator();
			while (slCursor.hasNext()) {
				BsonDocument dbObject = slCursor.next();

				if (OAuthUtil.isAccessible(userID, friendList, dbObject) == false) {
					continue;
				}

				if (format == null || format.equals("XML")) {
					TransactionEventReadConverter con = new TransactionEventReadConverter();
					JAXBElement element = new JAXBElement(new QName("TransactionEvent"), TransactionEventType.class,
							con.convert(dbObject));
					eventObjects.add(element);
				} else {
					dbObject.remove("_id");
					transactionJSONArray.put(dbObject);
				}
			}
			if (transactionJSONArray.length() > 0) {
				retJSON.put("TransactionEvent", transactionJSONArray);
			}
		}
		if (toGetTransformationEvent == true) {

			MongoCollection<BsonDocument> collection = Configuration.mongoDatabase.getCollection("TransformationEvent",
					BsonDocument.class);
			// Queries
			BsonArray queryList = makeQueryObjects("TransformationEvent", eventType, GE_eventTime, LT_eventTime,
					GE_recordTime, LT_recordTime, EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint,
					EQ_bizLocation, WD_bizLocation, EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC,
					MATCH_outputEPC, MATCH_anyEPC, MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass,
					MATCH_anyEPCClass, EQ_quantity, GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID,
					EXISTS_errorDeclaration, GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason,
					EQ_correctiveEventID, orderBy, orderDirection, eventCountLimit, maxEventCount, format, userID,
					friendList, paramMap);

			// Merge All the queries with $and
			BsonDocument baseQuery = new BsonDocument();
			FindIterable<BsonDocument> cursor;
			if (queryList.isEmpty() == false) {
				BsonArray aggreQueryList = new BsonArray();
				for (int i = 0; i < queryList.size(); i++) {
					aggreQueryList.add(queryList.get(i));
				}
				baseQuery.put("$and", aggreQueryList);
				// Query
				cursor = collection.find(baseQuery);
			} else {
				cursor = collection.find();
			}
			// Sort and Limit
			cursor = makeSortedLimitedCursor(cursor, orderBy, orderDirection, eventCountLimit);

			JSONArray transformationJSONArray = new JSONArray();

			MongoCursor<BsonDocument> slCursor = cursor.iterator();
			while (slCursor.hasNext()) {
				BsonDocument dbObject = slCursor.next();

				if (OAuthUtil.isAccessible(userID, friendList, dbObject) == false) {
					continue;
				}

				if (format == null || format.equals("XML")) {
					TransformationEventReadConverter con = new TransformationEventReadConverter();
					JAXBElement element = new JAXBElement(new QName("TransformationEvent"),
							TransformationEventType.class, con.convert(dbObject));
					eventObjects.add(element);
				} else {
					dbObject.remove("_id");
					transformationJSONArray.put(dbObject);
				}
			}
			if (transformationJSONArray.length() > 0) {
				retJSON.put("TransformationEvent", transformationJSONArray);
			}
		}

		// M44
		if (maxEventCount != null) {
			if (format == null || format.equals("XML")) {
				if (eventObjects.size() > maxEventCount) {

					return makeErrorResult("Violate maxEventCount", QueryTooLargeException.class);
				}
			} else {
				int cnt = 0;
				if (!retJSON.isNull("AggregationEvent")) {
					cnt += retJSON.getJSONArray("AggregationEvent").length();
				}
				if (!retJSON.isNull("ObjectEvent")) {
					cnt += retJSON.getJSONArray("ObjectEvent").length();
				}
				if (!retJSON.isNull("QuantityEvent")) {
					cnt += retJSON.getJSONArray("QuantityEvent").length();
				}
				if (!retJSON.isNull("TransactionEvent")) {
					cnt += retJSON.getJSONArray("TransactionEvent").length();
				}
				if (!retJSON.isNull("TransformationEvent")) {
					cnt += retJSON.getJSONArray("TransformationEvent").length();
				}
				if (cnt > maxEventCount) {
					return makeErrorResult("Violate maxEventCount", QueryTooLargeException.class);
				}
			}
		}
		if (format == null || format.equals("XML")) {
			StringWriter sw = new StringWriter();
			JAXB.marshal(epcisQueryDocumentType, sw);
			return sw.toString();
		} else {

			// BSONObject doc = (BSONObject) JSON.parse(retJSON.toString());
			// BasicBSONEncoder en = new BasicBSONEncoder();
			// System.out.println(doc.toString().length());
			// byte[] ret = en.encode(doc);
			// try {
			// FileUtils.writeByteArrayToFile(new File("/home/jack/temp.bson"),
			// ret);
			// } catch (IOException e) {
			// e.printStackTrace();
			// }
			// System.out.println(ret.length);
			// return new String(en.encode(doc));

			// BasicBSONDecoder de = new BasicBSONDecoder();
			// BSONObject obj = de.readObject(ret);
			// System.out.println(obj);

			return retJSON.toString(1);
		}
	}

	public String pollMasterDataQuery(String queryName, String vocabularyName, Boolean includeAttributes,
			Boolean includeChildren, String attributeNames, String eQ_name, String wD_name, String hASATTR,
			Integer maxElementCount, String format, Map<String, String> paramMap) {

		// Make Base Result Document
		EPCISQueryDocumentType epcisQueryDocumentType = null;
		JSONArray retArray = new JSONArray();

		if (format == null || format.equals("XML")) {
			epcisQueryDocumentType = makeBaseResultDocument(queryName);
		} else if (format.equals("JSON")) {
			// Do Nothing
		} else {
			return makeErrorResult("format param should be one of XML or JSON", QueryParameterException.class);
		}

		MongoCollection<BsonDocument> collection = Configuration.mongoDatabase.getCollection("MasterData",
				BsonDocument.class);

		// Make Query
		BsonArray queryList = makeMasterQueryObjects(vocabularyName, includeAttributes, includeChildren, attributeNames,
				eQ_name, wD_name, hASATTR, maxElementCount, paramMap);

		// Merge All the queries with $and
		BsonDocument baseQuery = new BsonDocument();
		FindIterable<BsonDocument> cursor;
		if (queryList.isEmpty() == false) {
			BsonArray aggreQueryList = new BsonArray();
			for (int i = 0; i < queryList.size(); i++) {
				aggreQueryList.add(queryList.get(i));
			}
			baseQuery.put("$and", aggreQueryList);
			// Query
			cursor = collection.find(baseQuery);
		} else {
			cursor = collection.find();
		}

		// Cursor needed to ordered
		List<VocabularyType> vList = new ArrayList<>();

		MongoCursor<BsonDocument> slCursor = cursor.iterator();
		while (slCursor.hasNext()) {
			BsonDocument dbObject = slCursor.next();

			if (format == null || format.equals("XML")) {
				MasterDataReadConverter con = new MasterDataReadConverter();
				VocabularyType vt = con.convert(dbObject);

				boolean isMatched = true;
				if (vt.getVocabularyElementList() != null) {
					if (vt.getVocabularyElementList().getVocabularyElement() != null) {
						List<VocabularyElementType> vetList = vt.getVocabularyElementList().getVocabularyElement();
						for (int i = 0; i < vetList.size(); i++) {
							VocabularyElementType vet = vetList.get(i);
							if (includeAttributes == false) {
								vet.setAttribute(null);
							} else if (includeAttributes == true && attributeNames != null) {
								/**
								 * attributeNames : If specified, only those
								 * attributes whose names match one of the
								 * specified names will be included in the
								 * results. If omitted, all attributes for each
								 * matching vocabulary element will be included.
								 * (To obtain a list of vocabulary element names
								 * with no attributes, specify false for
								 * includeAttributes.) The value of this
								 * parameter SHALL be ignored if
								 * includeAttributes is false. Note that this
								 * parameter does not affect which vocabulary
								 * elements are included in the result; it only
								 * limits which attributes will be included with
								 * each vocabulary element.
								 */
								isMatched = false;
								String[] attrArr = attributeNames.split(",");
								Set<String> attrSet = new HashSet<String>();
								for (int j = 0; j < attrArr.length; j++) {
									attrSet.add(attrArr[j].trim());
								}

								List<AttributeType> atList = vet.getAttribute();
								for (int j = 0; j < atList.size(); j++) {
									if (attrSet.contains(atList.get(j).getId())) {
										isMatched = true;
									}
								}
							}

							if (includeChildren == false) {
								vet.setChildren(null);
							}
						}
					}
				}
				if (isMatched == true)
					vList.add(vt);
			} else {
				boolean isMatched = true;
				dbObject.remove("_id");
				if (includeAttributes == false) {
					dbObject.remove("attributes");
				} else if (includeAttributes == true && attributeNames != null) {
					String[] attrArr = attributeNames.split(",");
					Set<String> attrSet = new HashSet<String>();
					for (int j = 0; j < attrArr.length; j++) {
						attrSet.add(attrArr[j].trim());
					}
					BsonDocument attrObject = dbObject.get("attributes").asDocument();
					isMatched = false;
					if (attrObject != null) {
						Iterator<String> attrKeys = attrObject.keySet().iterator();
						while (attrKeys.hasNext()) {
							String attrKey = attrKeys.next();
							if (attrSet.contains(attrKey)) {
								isMatched = true;
							}
						}
					}

				}
				if (includeChildren == false) {
					dbObject.remove("children");
				}

				if (isMatched == true) {
					retArray.put(dbObject);
				}
			}
		}

		if (format == null || format.equals("XML")) {
			QueryResultsBody qbt = epcisQueryDocumentType.getEPCISBody().getQueryResults().getResultsBody();

			VocabularyListType vlt = new VocabularyListType();
			vlt.setVocabulary(vList);
			qbt.setVocabularyList(vlt);
		}

		// M47
		if (maxElementCount != null) {
			try {
				if (format == null || format.equals("XML")) {
					if (vList.size() > maxElementCount) {
						return makeErrorResult("Too Large Master Data result", QueryTooLargeException.class);
					}
				} else {
					if (retArray.length() > maxElementCount) {
						return makeErrorResult("Too Large Master Data result", QueryTooLargeException.class);
					}
				}
			} catch (NumberFormatException e) {

			}
		}
		if (format == null || format.equals("XML")) {
			StringWriter sw = new StringWriter();
			JAXB.marshal(epcisQueryDocumentType, sw);
			return sw.toString();
		} else {
			return retArray.toString(1);
		}
	}

	public String generateCSV(List<String> valueList) {
		String returnValue = null;
		if (valueList.size() != 0)
			returnValue = "";
		for (int i = 0; i < valueList.size(); i++) {
			if (i == valueList.size() - 1) {
				returnValue += valueList.get(i).trim();
			} else {
				returnValue += valueList.get(i).trim() + ",";
			}
		}
		return returnValue;
	}

	// Soap Service Adaptor
	@SuppressWarnings("unchecked")
	public String poll(String queryName, QueryParams queryParams) {
		List<QueryParam> queryParamList = queryParams.getParam();

		String eventType = null;
		String GE_eventTime = null;
		String LT_eventTime = null;
		String GE_recordTime = null;
		String LT_recordTime = null;
		String EQ_action = null;
		String EQ_bizStep = null;
		String EQ_disposition = null;
		String EQ_readPoint = null;
		String WD_readPoint = null;
		String EQ_bizLocation = null;
		String WD_bizLocation = null;
		String EQ_transformationID = null;
		String MATCH_epc = null;
		String MATCH_parentID = null;
		String MATCH_inputEPC = null;
		String MATCH_outputEPC = null;
		String MATCH_anyEPC = null;
		String MATCH_epcClass = null;
		String MATCH_inputEPCClass = null;
		String MATCH_outputEPCClass = null;
		String MATCH_anyEPCClass = null;
		Integer EQ_quantity = null;
		Integer GT_quantity = null;
		Integer GE_quantity = null;
		Integer LT_quantity = null;
		Integer LE_quantity = null;

		String EQ_eventID = null;
		Boolean EXISTS_errorDeclaration = null;
		String GE_errorDeclarationTime = null;
		String LT_errorDeclarationTime = null;
		String EQ_errorReason = null;
		String EQ_correctiveEventID = null;

		String orderBy = null;
		String orderDirection = null;
		Integer eventCountLimit = null;
		Integer maxEventCount = null;

		String vocabularyName = null;
		Boolean includeAttributes = null;
		Boolean includeChildren = null;
		String attributeNames = null;
		String EQ_name = null;
		String WD_name = null;
		String HASATTR = null;
		Integer maxElementCount = null;

		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

		Map<String, String> extMap = new HashMap<String, String>();
		for (QueryParam qp : queryParamList) {
			String name = qp.getName();
			/*
			 * List<String>: If specified, the result will only include events
			 * whose type matches one of the types specified in the parameter
			 * value. Each element of the parameter value may be one of the
			 * following strings: ObjectEvent , AggregationEvent , QuantityEvent
			 * , TransactionEvent , or TransformationEvent . An element of the
			 * parameter value may also be the name of an extension event type.
			 * If omitted, all event types will be considered for inclusion in
			 * the result.
			 */
			if (name.equals("eventType")) {
				List<String> valueList = (List<String>) qp.getValue();
				eventType = generateCSV(valueList);
				continue;
			} else if (name.equals("GE_eventTime")) {
				GE_eventTime = sdf.format(((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTime());
				continue;
			} else if (name.equals("LT_eventTime")) {
				LT_eventTime = sdf.format(((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTime());
				continue;
			} else if (name.equals("GE_recordTime")) {
				GE_recordTime = sdf.format(((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTime());
				continue;
			} else if (name.equals("LT_recordTime")) {
				LT_recordTime = sdf.format(((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTime());
				continue;
			} else if (name.equals("EQ_action")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_action = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_bizStep")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_bizStep = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_disposition")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_disposition = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_readPoint")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_readPoint = generateCSV(valueList);
				continue;
			} else if (name.equals("WD_readPoint")) {
				List<String> valueList = (List<String>) qp.getValue();
				WD_readPoint = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_bizLocation")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_bizLocation = generateCSV(valueList);
				continue;
			} else if (name.equals("WD_bizLocation")) {
				List<String> valueList = (List<String>) qp.getValue();
				WD_bizLocation = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_transformationID")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_transformationID = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_epc")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_epc = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_parentID")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_parentID = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_inputEPC")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_inputEPC = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_outputEPC")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_outputEPC = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_anyEPC")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_anyEPC = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_epcClass")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_epcClass = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_inputEPCClass")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_inputEPCClass = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_outputEPCClass")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_outputEPCClass = generateCSV(valueList);
				continue;
			} else if (name.equals("MATCH_anyEPCClass")) {
				List<String> valueList = (List<String>) qp.getValue();
				MATCH_anyEPCClass = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_quantity")) {
				EQ_quantity = (Integer) qp.getValue();
				continue;
			} else if (name.equals("GT_quantity")) {
				GT_quantity = (Integer) qp.getValue();
				continue;
			} else if (name.equals("GE_quantity")) {
				GE_quantity = (Integer) qp.getValue();
				continue;
			} else if (name.equals("LT_quantity")) {
				LT_quantity = (Integer) qp.getValue();
				continue;
			} else if (name.equals("LE_quantity")) {
				LE_quantity = (Integer) qp.getValue();
				continue;
			} else if (name.equals("EQ_eventID")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_eventID = generateCSV(valueList);
				continue;
			} else if (name.equals("EXISTS_errorDeclaration")) {
				EXISTS_errorDeclaration = true;
				continue;
			} else if (name.equals("GE_errorDeclarationTime")) {
				GE_errorDeclarationTime = sdf
						.format(((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTime());
				continue;
			} else if (name.equals("LT_errorDeclarationTime")) {
				LT_errorDeclarationTime = sdf
						.format(((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTime());
				continue;
			} else if (name.equals("EQ_errorReason")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_errorReason = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_correctiveEventID")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_correctiveEventID = generateCSV(valueList);
				continue;
			} else if (name.equals("orderBy")) {
				orderBy = (String) qp.getValue();
				continue;
			} else if (name.equals("orderDirection")) {
				// ASC or DESC
				if (((String) qp.getValue()).equals("ASC") || ((String) qp.getValue()).equals("DESC")) {
					orderDirection = (String) qp.getValue();
				}
				continue;
			} else if (name.equals("eventCountLimit")) {
				eventCountLimit = (Integer) qp.getValue();
				continue;
			} else if (name.equals("maxEventCount")) {
				maxEventCount = (Integer) qp.getValue();
				continue;
			} else if (name.equals("vocabularyName")) {
				List<String> valueList = (List<String>) qp.getValue();
				vocabularyName = generateCSV(valueList);
				continue;
			} else if (name.equals("includeAttributes")) {
				includeAttributes = (Boolean) qp.getValue();
				continue;
			} else if (name.equals("includeChildren")) {
				includeChildren = (Boolean) qp.getValue();
				continue;
			} else if (name.equals("attributeNames")) {
				List<String> valueList = (List<String>) qp.getValue();
				attributeNames = generateCSV(valueList);
				continue;
			} else if (name.equals("EQ_name")) {
				List<String> valueList = (List<String>) qp.getValue();
				EQ_name = generateCSV(valueList);
				continue;
			} else if (name.equals("WD_name")) {
				List<String> valueList = (List<String>) qp.getValue();
				WD_name = generateCSV(valueList);
				continue;
			} else if (name.equals("HASATTR")) {
				List<String> valueList = (List<String>) qp.getValue();
				HASATTR = generateCSV(valueList);
				continue;
			} else if (name.equals("maxElementCount")) {
				maxElementCount = (Integer) qp.getValue();
				continue;
			} else {
				// EQ_bizTransaction_type : lString
				// EQ_source_type : lString
				// EQ_destination_type : lString
				// EQ_fieldname : lString
				// EQ_fieldname : int / float / time
				// GT|GE|LT|LE_fieldname : int / float / time
				// EQ_ILMD_fieldname : lString
				// EQ|GT|GE|LT|LE_ILMD_fieldname : int / float / time

				// EQ_INNER_fieldname : lString
				// EQ|GT|GE|LT|LE_INNER_fieldname : lString
				// EQ_INNER_ILMD_fieldname : lString
				// EQ|GT|GE|LT|LE_INNER_ILMD_fieldname : int / float / time

				// EXISTS_fieldname : Void
				// EXISTS_ILMD_fieldname : Void
				// EXISTS_ILMD_fieldname : Void

				// HASATTR_fieldname : lString
				// EQATTR_fieldname_attrname : lString

				// EQ_ERROR_DECLARATION_fieldname : lString
				// EQ|GT|GE|LT|GE_ERROR_DECLARATION_fieldname : int / float /
				// time
				// EQ_INNER_ERROR_DECLARATION_fieldname : lString
				// EQ|GT|GE|LT|GE_ERROR_DECLARATION_fieldname : int / float /
				// time

				if (qp.getValue() instanceof Integer) {
					extMap.put(name, String.valueOf(qp.getValue()) + "^int");
				} else if (qp.getValue() instanceof Float) {
					extMap.put(name, String.valueOf(qp.getValue()) + "^double");
				} else if (qp.getValue() instanceof XMLGregorianCalendar) {
					extMap.put(name,
							String.valueOf(
									((XMLGregorianCalendar) qp.getValue()).toGregorianCalendar().getTimeInMillis())
									+ "^long");
				} else if (qp.getValue() instanceof Void) {
					extMap.put(name, "true");
				} else if (qp.getValue() instanceof List<?>) {
					List<String> valueList = (List<String>) qp.getValue();
					extMap.put(name, generateCSV(valueList));
				}
			}
		}
		return poll(queryName, eventType, GE_eventTime, LT_eventTime, GE_recordTime, LT_recordTime, EQ_action,
				EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint, EQ_bizLocation, WD_bizLocation,
				EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC, MATCH_outputEPC, MATCH_anyEPC,
				MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass, MATCH_anyEPCClass, EQ_quantity, GT_quantity,
				GE_quantity, LT_quantity, LE_quantity, EQ_eventID, EXISTS_errorDeclaration, GE_errorDeclarationTime,
				LT_errorDeclarationTime, EQ_errorReason, EQ_correctiveEventID, orderBy, orderDirection, eventCountLimit,
				maxEventCount, vocabularyName, includeAttributes, includeChildren, attributeNames, EQ_name, WD_name,
				HASATTR, maxElementCount, null, null, null, extMap);
	}

	public String poll(String queryName, String eventType, String GE_eventTime, String LT_eventTime,
			String GE_recordTime, String LT_recordTime, String EQ_action, String EQ_bizStep, String EQ_disposition,
			String EQ_readPoint, String WD_readPoint, String EQ_bizLocation, String WD_bizLocation,
			String EQ_transformationID, String MATCH_epc, String MATCH_parentID, String MATCH_inputEPC,
			String MATCH_outputEPC, String MATCH_anyEPC, String MATCH_epcClass, String MATCH_inputEPCClass,
			String MATCH_outputEPCClass, String MATCH_anyEPCClass, Integer EQ_quantity, Integer GT_quantity,
			Integer GE_quantity, Integer LT_quantity, Integer LE_quantity, String EQ_eventID,
			Boolean EXISTS_errorDeclaration, String GE_errorDeclarationTime, String LT_errorDeclarationTime,
			String EQ_errorReason, String EQ_correctiveEventID, String orderBy, String orderDirection,
			Integer eventCountLimit, Integer maxEventCount, String vocabularyName, Boolean includeAttributes,
			Boolean includeChildren, String attributeNames, String EQ_name, String WD_name, String HASATTR,
			Integer maxElementCount, String format, String userID, List<String> friendList,
			Map<String, String> paramMap) {

		// M24
		if (queryName == null) {
			// It is not possible, automatically filtered by URI param
			return makeErrorResult("queryName is mandatory field in poll method", QueryParameterException.class);
		}

		if (queryName.equals("SimpleEventQuery"))
			return pollEventQuery(queryName, eventType, GE_eventTime, LT_eventTime, GE_recordTime, LT_recordTime,
					EQ_action, EQ_bizStep, EQ_disposition, EQ_readPoint, WD_readPoint, EQ_bizLocation, WD_bizLocation,
					EQ_transformationID, MATCH_epc, MATCH_parentID, MATCH_inputEPC, MATCH_outputEPC, MATCH_anyEPC,
					MATCH_epcClass, MATCH_inputEPCClass, MATCH_outputEPCClass, MATCH_anyEPCClass, EQ_quantity,
					GT_quantity, GE_quantity, LT_quantity, LE_quantity, EQ_eventID, EXISTS_errorDeclaration,
					GE_errorDeclarationTime, LT_errorDeclarationTime, EQ_errorReason, EQ_correctiveEventID, orderBy,
					orderDirection, eventCountLimit, maxEventCount, format, userID, friendList, paramMap);

		if (queryName.equals("SimpleMasterDataQuery"))
			return pollMasterDataQuery(queryName, vocabularyName, includeAttributes, includeChildren, attributeNames,
					EQ_name, WD_name, HASATTR, maxElementCount, format, paramMap);
		return "";
	}

	private String checkConstraintSimpleEventQuery(String queryName, String eventType, String GE_eventTime,
			String LT_eventTime, String GE_recordTime, String LT_recordTime, String EQ_action, String EQ_bizStep,
			String EQ_disposition, String EQ_readPoint, String WD_readPoint, String EQ_bizLocation,
			String WD_bizLocation, String EQ_transformationID, String MATCH_epc, String MATCH_parentID,
			String MATCH_inputEPC, String MATCH_outputEPC, String MATCH_anyEPC, String MATCH_epcClass,
			String MATCH_inputEPCClass, String MATCH_outputEPCClass, String MATCH_anyEPCClass, Integer EQ_quantity,
			Integer GT_quantity, Integer GE_quantity, Integer LT_quantity, Integer LE_quantity, String EQ_eventID,
			Boolean EXISTS_errorDeclaration, String GE_errorDeclarationTime, String LT_errorDeclarationTime,
			String EQ_errorReason, String EQ_correctiveEventID, String orderBy, String orderDirection,
			Integer eventCountLimit, Integer maxEventCount, Map<String, String> paramMap) {

		// M27
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
			if (GE_eventTime != null)
				sdf.parse(GE_eventTime);
			if (LT_eventTime != null)
				sdf.parse(LT_eventTime);
			if (GE_recordTime != null)
				sdf.parse(GE_recordTime);
			if (LT_recordTime != null)
				sdf.parse(LT_recordTime);
		} catch (ParseException e) {
			try {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
				if (GE_eventTime != null)
					sdf.parse(GE_eventTime);
				if (LT_eventTime != null)
					sdf.parse(LT_eventTime);
				if (GE_recordTime != null)
					sdf.parse(GE_recordTime);
				if (LT_recordTime != null)
					sdf.parse(LT_recordTime);
			} catch (ParseException e1) {
				return makeErrorResult(e.toString(), QueryParameterException.class);
			}
		}

		// M27
		if (orderBy != null) {
			if (!orderBy.equals("eventTime") && !orderBy.equals("recordTime")) {
				return makeErrorResult("orderBy should be eventTime or recordTime", QueryParameterException.class);
			}
			if (orderDirection != null) {
				if (!orderDirection.equals("ASC") && !orderDirection.equals("DESC")) {
					return makeErrorResult("orderDirection should be ASC or DESC", QueryParameterException.class);
				}
			}
		}

		// M27
		if (eventCountLimit != null) {
			if (eventCountLimit <= 0) {
				return makeErrorResult("eventCount should be natural number", QueryParameterException.class);
			}
		}

		// M27
		if (maxEventCount != null) {
			if (maxEventCount <= 0) {
				return makeErrorResult("maxEventCount should be natural number", QueryParameterException.class);
			}
		}

		// M39
		if (EQ_action != null) {
			if (!EQ_action.equals("ADD") && !EQ_action.equals("OBSERVE") && !EQ_action.equals("DELETE")) {
				return makeErrorResult("EQ_action: ADD | OBSERVE | DELETE", QueryParameterException.class);
			}
		}

		// M42
		if (eventCountLimit != null && maxEventCount != null) {
			return makeErrorResult("One of eventCountLimit and maxEventCount should be omitted",
					QueryParameterException.class);
		}
		return null;
	}

	private EPCISQueryDocumentType makeBaseResultDocument(String queryName) {
		// Make Base Result Document
		EPCISQueryDocumentType epcisQueryDocumentType = new EPCISQueryDocumentType();
		EPCISQueryBodyType epcisBody = new EPCISQueryBodyType();
		epcisQueryDocumentType.setEPCISBody(epcisBody);
		QueryResults queryResults = new QueryResults();
		queryResults.setQueryName(queryName);
		epcisBody.setQueryResults(queryResults);
		QueryResultsBody queryResultsBody = new QueryResultsBody();
		queryResults.setResultsBody(queryResultsBody);
		EventListType eventListType = new EventListType();
		queryResultsBody.setEventList(eventListType);
		// Object instanceof JAXBElement
		List<Object> eventObjects = new ArrayList<Object>();
		eventListType.setObjectEventOrAggregationEventOrQuantityEvent(eventObjects);
		return epcisQueryDocumentType;
	}

	boolean isExtraParameter(String paramName) {

		if (paramName.contains("eventTime"))
			return false;
		if (paramName.contains("recordTime"))
			return false;
		if (paramName.contains("action"))
			return false;
		if (paramName.contains("bizStep"))
			return false;
		if (paramName.contains("disposition"))
			return false;
		if (paramName.contains("readPoint"))
			return false;
		if (paramName.contains("bizLocation"))
			return false;
		if (paramName.contains("bizTransaction"))
			return false;
		if (paramName.contains("source"))
			return false;
		if (paramName.contains("destination"))
			return false;
		if (paramName.contains("transformationID"))
			return false;
		return true;
	}

	@SuppressWarnings("rawtypes")
	private String makeErrorResult(String err, Class type) {
		if (type == InvalidURIException.class) {
			InvalidURIException e = new InvalidURIException();
			e.setReason(err);
			EPCISQueryDocumentType retDoc = new EPCISQueryDocumentType();
			EPCISQueryBodyType retBody = new EPCISQueryBodyType();
			retBody.setInvalidURIException(e);
			retDoc.setEPCISBody(retBody);
			StringWriter sw = new StringWriter();
			JAXB.marshal(retDoc, sw);
			return sw.toString();
		}
		if (type == QueryParameterException.class) {
			QueryParameterException e = new QueryParameterException();
			e.setReason(err);
			EPCISQueryDocumentType retDoc = new EPCISQueryDocumentType();
			EPCISQueryBodyType retBody = new EPCISQueryBodyType();
			retBody.setQueryParameterException(e);
			retDoc.setEPCISBody(retBody);
			StringWriter sw = new StringWriter();
			JAXB.marshal(retDoc, sw);
			return sw.toString();
		}
		if (type == SubscriptionControlsException.class) {
			SubscriptionControlsException e = new SubscriptionControlsException();
			e.setReason(err);
			EPCISQueryDocumentType retDoc = new EPCISQueryDocumentType();
			EPCISQueryBodyType retBody = new EPCISQueryBodyType();
			retBody.setSubscriptionControlsException(e);
			retDoc.setEPCISBody(retBody);
			StringWriter sw = new StringWriter();
			JAXB.marshal(retDoc, sw);
			return sw.toString();
		}
		if (type == QueryTooLargeException.class) {
			QueryTooLargeException e = new QueryTooLargeException();
			e.setReason(err);
			EPCISQueryDocumentType retDoc = new EPCISQueryDocumentType();
			EPCISQueryBodyType retBody = new EPCISQueryBodyType();
			retBody.setQueryTooLargeException(e);
			retDoc.setEPCISBody(retBody);
			StringWriter sw = new StringWriter();
			JAXB.marshal(retDoc, sw);
			return sw.toString();
		}
		if (type == SubscribeNotPermittedException.class) {
			SubscribeNotPermittedException e = new SubscribeNotPermittedException();
			e.setReason(err);
			EPCISQueryDocumentType retDoc = new EPCISQueryDocumentType();
			EPCISQueryBodyType retBody = new EPCISQueryBodyType();
			retBody.setSubscribeNotPermittedException(e);
			retDoc.setEPCISBody(retBody);
			StringWriter sw = new StringWriter();
			JAXB.marshal(retDoc, sw);
			return sw.toString();
		}
		return null;
	}

	private FindIterable<BsonDocument> makeSortedLimitedCursor(FindIterable<BsonDocument> cursor, String orderBy,
			String orderDirection, Integer eventCountLimit) {
		/**
		 * orderBy : If specified, names a single field that will be used to
		 * order the results. The orderDirection field specifies whether the
		 * ordering is in ascending sequence or descending sequence. Events
		 * included in the result that lack the specified field altogether may
		 * occur in any position within the result event list. The value of this
		 * parameter SHALL be one of: eventTime, recordTime, or the fully
		 * qualified name of an extension field whose type is Int, Float, Time,
		 * or String. A fully qualified fieldname is constructed as for the
		 * EQ_fieldname parameter. In the case of a field of type String, the
		 * ordering SHOULD be in lexicographic order based on the Unicode
		 * encoding of the strings, or in some other collating sequence
		 * appropriate to the locale. If omitted, no order is specified. The
		 * implementation MAY order the results in any order it chooses, and
		 * that order MAY differ even when the same query is executed twice on
		 * the same data. (In EPCIS 1.0, the value quantity was also permitted,
		 * but its use is deprecated in EPCIS 1.1.)
		 * 
		 * orderDirection : If specified and orderBy is also specified,
		 * specifies whether the results are ordered in ascending or descending
		 * sequence according to the key specified by orderBy. The value of this
		 * parameter must be one of ASC (for ascending order) or DESC (for
		 * descending order); if not, the implementation SHALL raise a
		 * QueryParameterException. If omitted, defaults to DESC.
		 */

		// Update Query with ORDER and LIMIT
		if (orderBy != null) {
			// Currently only eventTime, recordTime can be used
			if (orderBy.trim().equals("eventTime")) {
				if (orderDirection != null) {
					if (orderDirection.trim().equals("ASC")) {
						cursor = cursor.sort(new BsonDocument("eventTime", new BsonInt32(1)));
					} else if (orderDirection.trim().equals("DESC")) {
						cursor = cursor.sort(new BsonDocument("eventTime", new BsonInt32(-1)));
					}
				}
			} else if (orderBy.trim().equals("recordTime")) {
				if (orderDirection != null) {
					if (orderDirection.trim().equals("ASC")) {
						cursor = cursor.sort(new BsonDocument("recordTime", new BsonInt32(1)));
					} else if (orderDirection.trim().equals("DESC")) {
						cursor = cursor.sort(new BsonDocument("recordTime", new BsonInt32(-1)));
					}
				}
			}
		}

		/**
		 * eventCountLimit: If specified, the results will only include the
		 * first N events that match the other criteria, where N is the value of
		 * this parameter. The ordering specified by the orderBy and
		 * orderDirection parameters determine the meaning of “first” for this
		 * purpose. If omitted, all events matching the specified criteria will
		 * be included in the results. This parameter and maxEventCount are
		 * mutually exclusive; if both are specified, a QueryParameterException
		 * SHALL be raised. This parameter may only be used when orderBy is
		 * specified; if orderBy is omitted and eventCountLimit is specified, a
		 * QueryParameterException SHALL be raised. This parameter differs from
		 * maxEventCount in that this parameter limits the amount of data
		 * returned, whereas maxEventCount causes an exception to be thrown if
		 * the limit is exceeded.
		 */
		if (eventCountLimit != null) {
			try {
				cursor = cursor.limit(eventCountLimit);
			} catch (NumberFormatException nfe) {
				Configuration.logger.log(Level.ERROR, nfe.toString());
			}
		}

		return cursor;
	}

	private BsonArray makeQueryObjects(String queryName, String eventType, String GE_eventTime, String LT_eventTime,
			String GE_recordTime, String LT_recordTime, String EQ_action, String EQ_bizStep, String EQ_disposition,
			String EQ_readPoint, String WD_readPoint, String EQ_bizLocation, String WD_bizLocation,
			String EQ_transformationID, String MATCH_epc, String MATCH_parentID, String MATCH_inputEPC,
			String MATCH_outputEPC, String MATCH_anyEPC, String MATCH_epcClass, String MATCH_inputEPCClass,
			String MATCH_outputEPCClass, String MATCH_anyEPCClass, Integer EQ_quantity, Integer GT_quantity,
			Integer GE_quantity, Integer LT_quantity, Integer LE_quantity, String EQ_eventID,
			Boolean EXISTS_errorDeclaration, String GE_errorDeclarationTime, String LT_errorDeclarationTime,
			String EQ_errorReason, String EQ_correctiveEventID, String orderBy, String orderDirection,
			Integer eventCountLimit, Integer maxEventCount, String format, String userID, List<String> friendList,
			Map<String, String> paramMap) {

		BsonArray queryList = new BsonArray();
		/**
		 * GE_eventTime: If specified, only events with eventTime greater than
		 * or equal to the specified value will be included in the result. If
		 * omitted, events are included regardless of their eventTime (unless
		 * constrained by the LT_eventTime parameter). Example:
		 * 2014-08-11T19:57:59.717+09:00 SimpleDateFormat sdf = new
		 * SimpleDateFormat( "yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
		 * eventTime.setTime(sdf.parse(timeString)); e.g.
		 * 1988-07-04T12:08:56.235-07:00
		 * 
		 * Verified
		 */
		if (GE_eventTime != null) {
			long geEventTimeMillis = getTimeMillis(GE_eventTime);
			BsonDocument query = new BsonDocument();
			query.put("eventTime", new BsonDocument("$gte", new BsonInt64(geEventTimeMillis)));
			queryList.add(query);
		}
		/**
		 * LT_eventTime: If specified, only events with eventTime less than the
		 * specified value will be included in the result. If omitted, events
		 * are included regardless of their eventTime (unless constrained by the
		 * GE_eventTime parameter).
		 * 
		 * Verified
		 */
		if (LT_eventTime != null) {
			long ltEventTimeMillis = getTimeMillis(LT_eventTime);
			BsonDocument query = new BsonDocument();
			query.put("eventTime", new BsonDocument("$lt", new BsonInt64(ltEventTimeMillis)));
			queryList.add(query);
		}
		/**
		 * GE_recordTime: If provided, only events with recordTime greater than
		 * or equal to the specified value will be returned. The automatic
		 * limitation based on event record time (Section 8.2.5.2) may
		 * implicitly provide a constraint similar to this parameter. If
		 * omitted, events are included regardless of their recordTime, other
		 * than automatic limitation based on event record time (Section
		 * 8.2.5.2).
		 * 
		 * Verified
		 */
		if (GE_recordTime != null) {
			long geRecordTimeMillis = getTimeMillis(GE_recordTime);
			BsonDocument query = new BsonDocument();
			query.put("recordTime", new BsonDocument("$gte", new BsonInt64(geRecordTimeMillis)));
			queryList.add(query);
		}
		/**
		 * LE_recordTime: If provided, only events with recordTime less than the
		 * specified value will be returned. If omitted, events are included
		 * regardless of their recordTime (unless constrained by the
		 * GE_recordTime parameter or the automatic limitation based on event
		 * record time).
		 * 
		 * Verified
		 */
		if (LT_recordTime != null) {
			long ltRecordTimeMillis = getTimeMillis(LT_recordTime);
			BsonDocument query = new BsonDocument();
			query.put("recordTime", new BsonDocument("$lt", new BsonInt64(ltRecordTimeMillis)));
			queryList.add(query);
		}

		/**
		 * EQ_action: If specified, the result will only include events that (a)
		 * have an action field; and where (b) the value of the action field
		 * matches one of the specified values. The elements of the value of
		 * this parameter each must be one of the strings ADD, OBSERVE, or
		 * DELETE; if not, the implementation SHALL raise a
		 * QueryParameterException. If omitted, events are included regardless
		 * of their action field.
		 * 
		 * Verified
		 */
		if (EQ_action != null) {
			// Constrained already checked
			BsonDocument query = new BsonDocument();
			query.put("action", new BsonString(EQ_action));
			queryList.add(query);
		}
		/**
		 * EQ_bizStep: If specified, the result will only include events that
		 * (a) have a non-null bizStep field; and where (b) the value of the
		 * bizStep field matches one of the specified values. If this parameter
		 * is omitted, events are returned regardless of the value of the
		 * bizStep field or whether the bizStep field exists at all.
		 * 
		 * Verified
		 */
		if (EQ_bizStep != null) {
			BsonDocument query = getINQueryObject("bizStep", EQ_bizStep);
			if (query != null)
				queryList.add(query);
		}
		/**
		 * EQ_disposition: Like the EQ_bizStep parameter, but for the
		 * disposition field.
		 * 
		 * Verified
		 */
		if (EQ_disposition != null) {
			BsonDocument query = getINQueryObject("disposition", EQ_disposition);
			if (query != null)
				queryList.add(query);
		}
		/**
		 * EQ_readPoint: If specified, the result will only include events that
		 * (a) have a non-null readPoint field; and where (b) the value of the
		 * readPoint field matches one of the specified values. If this
		 * parameter and WD_readPoint are both omitted, events are returned
		 * regardless of the value of the readPoint field or whether the
		 * readPoint field exists at all.
		 */
		Set<String> readPointSet = new HashSet<String>();
		if (EQ_readPoint != null) {
			String[] eqArr = EQ_readPoint.split(",");
			for (int i = 0; i < eqArr.length; i++) {
				String eqString = eqArr[i].trim();
				readPointSet.add(eqString);
			}
		}

		/**
		 * WD_readPoint: If specified, the result will only include events that
		 * (a) have a non-null readPoint field; and where (b) the value of the
		 * readPoint field matches one of the specified values, or is a direct
		 * or indirect descendant of one of the specified values. The meaning of
		 * “direct or indirect descendant” is specified by master data, as
		 * described in Section 6.5. (WD is an abbreviation for “with
		 * descendants.”) If this parameter and EQ_readPoint are both omitted,
		 * events are returned regardless of the value of the readPoint field or
		 * whether the readPoint field exists at all.
		 */

		if (WD_readPoint != null) {

			String[] eqArr = WD_readPoint.split(",");
			for (int i = 0; i < eqArr.length; i++) {
				eqArr[i] = eqArr[i].trim();
			}
			for (int i = 0; i < eqArr.length; i++) {
				// Invoke vocabulary query with EQ_name and includeChildren
				readPointSet = getWDList(readPointSet, eqArr[i]);
			}
		}

		if (!readPointSet.isEmpty()) {
			BsonDocument query = getINQueryObject("readPoint.id", readPointSet);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * EQ_bizLocation: Like the EQ_readPoint parameter, but for the
		 * bizLocation field.
		 */
		Set<String> bizLocationSet = new HashSet<String>();
		if (EQ_bizLocation != null) {
			String[] eqArr = EQ_bizLocation.split(",");
			for (int i = 0; i < eqArr.length; i++) {
				String eqString = eqArr[i].trim();
				bizLocationSet.add(eqString);
			}
		}
		/**
		 * WD_bizLocation: Like the WD_readPoint parameter, but for the
		 * bizLocation field.
		 */
		if (WD_bizLocation != null) {
			String[] eqArr = WD_bizLocation.split(",");
			for (int i = 0; i < eqArr.length; i++) {
				eqArr[i] = eqArr[i].trim();
			}
			for (int i = 0; i < eqArr.length; i++) {
				// Invoke vocabulary query with EQ_name and includeChildren
				bizLocationSet = getWDList(bizLocationSet, eqArr[i]);
			}
		}

		if (!bizLocationSet.isEmpty()) {
			BsonDocument query = getINQueryObject("bizLocation.id", bizLocationSet);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * EQ_transformationID: If this parameter is specified, the result will
		 * only include events that (a) have a transformationID field (that is,
		 * TransformationEvents or extension event type that extend
		 * TransformationEvent); and where (b) the transformationID field is
		 * equal to one of the values specified in this parameter.
		 */
		if (EQ_transformationID != null) {
			BsonDocument query = getINQueryObject("transformationID", EQ_transformationID);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_epc: If this parameter is specified, the result will only
		 * include events that (a) have an epcList or a childEPCs field (that
		 * is, ObjectEvent, AggregationEvent, TransactionEvent or extension
		 * event types that extend one of those three); and where (b) one of the
		 * EPCs listed in the epcList or childEPCs field (depending on event
		 * type) matches one of the EPC patterns or URIs specified in this
		 * parameter, where the meaning of “matches” is as specified in Section
		 * 8.2.7.1.1. If this parameter is omitted, events are included
		 * regardless of their epcList or childEPCs field or whether the epcList
		 * or childEPCs field exists.
		 * 
		 * Somewhat verified
		 */
		if (MATCH_epc != null) {
			BsonDocument query = getINQueryObject(new String[] { "epcList.epc", "childEPCs.epc" }, MATCH_epc);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_parentID: Like MATCH_epc, but matches the parentID field of
		 * AggregationEvent, the parentID field of TransactionEvent, and
		 * extension event types that extend either AggregationEvent or
		 * TransactionEvent. The meaning of “matches” is as specified in Section
		 * 8.2.7.1.1.
		 */
		if (MATCH_parentID != null) {
			BsonDocument query = getINQueryObject("parentID", MATCH_parentID);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_inputEPC: If this parameter is specified, the result will only
		 * include events that (a) have an inputEPCList (that is,
		 * TransformationEvent or an extension event type that extends
		 * TransformationEvent); and where (b) one of the EPCs listed in the
		 * inputEPCList field matches one of the EPC patterns or URIs specified
		 * in this parameter. The meaning of “matches” is as specified in
		 * Section 8.2.7.1.1. If this parameter is omitted, events are included
		 * regardless of their inputEPCList field or whether the inputEPCList
		 * field exists.
		 */
		if (MATCH_inputEPC != null) {
			BsonDocument query = getINQueryObject("inputEPCList.epc", MATCH_inputEPC);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_outputEPC: If this parameter is specified, the result will only
		 * include events that (a) have an inputEPCList (that is,
		 * TransformationEvent or an extension event type that extends
		 * TransformationEvent); and where (b) one of the EPCs listed in the
		 * inputEPCList field matches one of the EPC patterns or URIs specified
		 * in this parameter. The meaning of “matches” is as specified in
		 * Section 8.2.7.1.1. If this parameter is omitted, events are included
		 * regardless of their inputEPCList field or whether the inputEPCList
		 * field exists.
		 */
		if (MATCH_outputEPC != null) {
			BsonDocument query = getINQueryObject("outputEPCList.epc", MATCH_outputEPC);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_anyEPC: If this parameter is specified, the result will only
		 * include events that (a) have an epcList field, a childEPCs field, a
		 * parentID field, an inputEPCList field, or an outputEPCList field
		 * (that is, ObjectEvent, AggregationEvent, TransactionEvent,
		 * TransformationEvent, or extension event types that extend one of
		 * those four); and where (b) the parentID field or one of the EPCs
		 * listed in the epcList, childEPCs, inputEPCList, or outputEPCList
		 * field (depending on event type) matches one of the EPC patterns or
		 * URIs specified in this parameter. The meaning of “matches” is as
		 * specified in Section 8.2.7.1.1.
		 */

		if (MATCH_anyEPC != null) {
			BsonDocument query = getINQueryObject(new String[] { "epcList.epc", "childEPCs.epc", "inputEPCList.epc",
					"outputEPCList.epc", "parentID" }, MATCH_anyEPC);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_epcClass: If this parameter is specified, the result will only
		 * include events that (a) have a quantityList or a childQuantityList
		 * field (that is, ObjectEvent, AggregationEvent, TransactionEvent or
		 * extension event types that extend one of those three); and where (b)
		 * one of the EPC classes listed in the quantityList or
		 * childQuantityList field (depending on event type) matches one of the
		 * EPC patterns or URIs specified in this parameter. The result will
		 * also include QuantityEvents whose epcClass field matches one of the
		 * EPC patterns or URIs specified in this parameter. The meaning of
		 * “matches” is as specified in Section 8.2.7.1.1.
		 */
		if (MATCH_epcClass != null) {
			BsonDocument query = getINQueryObject(
					new String[] { "extension.quantityList.epcClass", "extension.childQuantityList.epcClass" },
					MATCH_epcClass);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_inputEPCClass: If this parameter is specified, the result will
		 * only include events that (a) have an inputQuantityList field (that
		 * is, TransformationEvent or extension event types that extend it); and
		 * where (b) one of the EPC classes listed in the inputQuantityList
		 * field (depending on event type) matches one of the EPC patterns or
		 * URIs specified in this parameter. The meaning of “matches” is as
		 * specified in Section 8.2.7.1.1.
		 */
		if (MATCH_inputEPCClass != null) {
			BsonDocument query = getINQueryObject("inputQuantityList.epcClass", MATCH_inputEPCClass);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_outputEPCClass: If this parameter is specified, the result will
		 * only include events that (a) have an outputQuantityList field (that
		 * is, TransformationEvent or extension event types that extend it); and
		 * where (b) one of the EPC classes listed in the outputQuantityList
		 * field (depending on event type) matches one of the EPC patterns or
		 * URIs specified in this parameter. The meaning of “matches” is as
		 * specified in Section 8.2.7.1.1.
		 */

		if (MATCH_outputEPCClass != null) {
			BsonDocument query = getINQueryObject("outputQuantityList.epcClass", MATCH_outputEPCClass);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * MATCH_anyEPCClass: If this parameter is specified, the result will
		 * only include events that (a) have a quantityList, childQuantityList,
		 * inputQuantityList, or outputQuantityList field (that is, ObjectEvent,
		 * AggregationEvent, TransactionEvent, TransformationEvent, or extension
		 * event types that extend one of those four); and where (b) one of the
		 * EPC classes listed in any of those fields matches one of the EPC
		 * patterns or URIs specified in this parameter. The result will also
		 * include QuantityEvents whose epcClass field matches one of the EPC
		 * patterns or URIs specified in this parameter. The meaning of
		 * “matches” is as specified in Section 8.2.7.1.1.
		 */
		if (MATCH_anyEPCClass != null) {
			BsonDocument query = getINQueryObject(
					new String[] { "extension.quantityList.epcClass", "extension.childQuantityList.epcClass",
							"inputQuantityList.epcClass", "outputQuantityList.epcClass" },
					MATCH_anyEPCClass);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * (DEPCRECATED in EPCIS 1.1) EQ_quantity; GT_quantity; GE_quantity;
		 * LT_quantity; LE_quantity
		 **/

		/**
		 * EQ_fieldname: This is not a single parameter, but a family of
		 * parameters. If a parameter of this form is specified, the result will
		 * only include events that (a) have a field named fieldname whose type
		 * is either String or a vocabulary type; and where (b) the value of
		 * that field matches one of the values specified in this parameter.
		 * Fieldname is the fully qualified name of an extension field. The name
		 * of an extension field is an XML qname; that is, a pair consisting of
		 * an XML namespace URI and a name. The name of the corresponding query
		 * parameter is constructed by concatenating the following: the string
		 * EQ_, the namespace URI for the extension field, a pound sign (#), and
		 * the name of the extension field.
		 */

		Iterator<String> paramIter = paramMap.keySet().iterator();
		while (paramIter.hasNext()) {
			String paramName = paramIter.next();
			String paramValues = paramMap.get(paramName);

			/**
			 * EQ_bizTransaction_type: This is not a single parameter, but a
			 * family of parameters. If a parameter of this form is specified,
			 * the result will only include events that (a) include a
			 * bizTransactionList; (b) where the business transaction list
			 * includes an entry whose type subfield is equal to type extracted
			 * from the name of this parameter; and (c) where the bizTransaction
			 * subfield of that entry is equal to one of the values specified in
			 * this parameter.
			 */
			if (paramName.contains("EQ_bizTransaction_")) {
				String type = paramName.substring(18, paramName.length());
				BsonDocument query = getINFamilyQueryObject(type, "bizTransactionList", paramValues);
				if (query != null)
					queryList.add(query);
			}

			/**
			 * EQ_source_type: This is not a single parameter, but a family of
			 * parameters. If a parameter of this form is specified, the result
			 * will only include events that (a) include a sourceList; (b) where
			 * the source list includes an entry whose type subfield is equal to
			 * type extracted from the name of this parameter; and (c) where the
			 * source subfield of that entry is equal to one of the values
			 * specified in this parameter.
			 */

			if (paramName.contains("EQ_source_")) {
				String type = paramName.substring(10, paramName.length());
				if (eventType.equals("AggregationEvent") || eventType.equals("ObjectEvent")
						|| eventType.equals("TransactionEvent")) {
					BsonDocument query = getINFamilyQueryObject(type, "extension.sourceList", paramValues);
					if (query != null)
						queryList.add(query);
				}
				if (eventType.equals("TransformationEvent")) {
					BsonDocument query = getINFamilyQueryObject(type, "sourceList", paramValues);
					if (query != null)
						queryList.add(query);
				}
			}

			/**
			 * EQ_destination_type: This is not a single parameter, but a family
			 * of parameters. If a parameter of this form is specified, the
			 * result will only include events that (a) include a
			 * destinationList; (b) where the destination list includes an entry
			 * whose type subfield is equal to type extracted from the name of
			 * this parameter; and (c) where the destination subfield of that
			 * entry is equal to one of the values specified in this parameter.
			 */
			if (paramName.contains("EQ_destination_")) {
				String type = paramName.substring(15, paramName.length());
				if (eventType.equals("AggregationEvent") || eventType.equals("ObjectEvent")
						|| eventType.equals("TransactionEvent")) {
					BsonDocument query = getINFamilyQueryObject(type, "extension.destinationList", paramValues);
					if (query != null)
						queryList.add(query);
				}
				if (eventType.equals("TransformationEvent")) {
					BsonDocument query = getINFamilyQueryObject(type, "destinationList", paramValues);
					if (query != null)
						queryList.add(query);
				}
			}
			boolean isExtraParam = isExtraParameter(paramName);

			if (isExtraParam == true) {

				/**
				 * EQ_fieldname: This is not a single parameter, but a family of
				 * parameters. If a parameter of this form is specified, the
				 * result will only include events that (a) have a field named
				 * fieldname whose type is either String or a vocabulary type;
				 * and where (b) the value of that field matches one of the
				 * values specified in this parameter. Fieldname is the fully
				 * qualified name of an extension field. The name of an
				 * extension field is an XML qname; that is, a pair consisting
				 * of an XML namespace URI and a name. The name of the
				 * corresponding query parameter is constructed by concatenating
				 * the following: the string EQ_, the namespace URI for the
				 * extension field, a pound sign (#), and the name of the
				 * extension field.
				 */
				if (paramName.startsWith("EQ_")) {
					String type = paramName.substring(3, paramName.length());
					/*
					 * if (eventType.equals("AggregationEvent") ||
					 * eventType.equals("ObjectEvent") ||
					 * eventType.equals("TransactionEvent")) { DBObject query =
					 * getINExtensionQueryObject(type, new String[] {
					 * "extension.extension.any." + type,
					 * "extension.extension.otherAttributes." + type },
					 * paramValues); if (query != null) queryList.add(query); }
					 * if (eventType.equals("QuantityEvent") ||
					 * eventType.equals("TransformationEvent")) { DBObject query
					 * = getINExtensionQueryObject( type, new String[] {
					 * "extension.any." + type, "extension.otherAttributes." +
					 * type }, paramValues); if (query != null)
					 * queryList.add(query); }
					 */
					BsonDocument query = getINExtensionQueryObject(type,
							new String[] { "any." + type, "otherAttributes." + type }, paramValues);
					if (query != null)
						queryList.add(query);
				}

				/**
				 * GT/GE/LT/LE_fieldname: Like EQ_fieldname as described above,
				 * but may be applied to a field of type Int, Float, or Time.
				 * The result will include events that (a) have a field named
				 * fieldname; and where (b) the type of the field matches the
				 * type of this parameter (Int, Float, or Time); and where (c)
				 * the value of the field is greater than the specified value.
				 * Fieldname is constructed as for EQ_fieldname.
				 */

				if (paramName.startsWith("GT_") || paramName.startsWith("GE_") || paramName.startsWith("LT_")
						|| paramName.startsWith("LE_")) {
					String type = paramName.substring(3, paramName.length());

					/*
					 * if (eventType.equals("AggregationEvent") ||
					 * eventType.equals("ObjectEvent") ||
					 * eventType.equals("TransactionEvent")) { if
					 * (paramName.startsWith("GT_")) { DBObject query =
					 * getCompExtensionQueryObject( type, new String[] {
					 * "extension.extension.any." + type,
					 * "extension.extension.otherAttributes." + type },
					 * paramValues, "GT"); if (query != null)
					 * queryList.add(query); } if (paramName.startsWith("GE_"))
					 * { DBObject query = getCompExtensionQueryObject( type, new
					 * String[] { "extension.extension.any." + type,
					 * "extension.extension.otherAttributes." + type },
					 * paramValues, "GE"); if (query != null)
					 * queryList.add(query); } if (paramName.startsWith("LT_"))
					 * { DBObject query = getCompExtensionQueryObject( type, new
					 * String[] { "extension.extension.any." + type,
					 * "extension.extension.otherAttributes." + type },
					 * paramValues, "LT"); if (query != null)
					 * queryList.add(query); } if (paramName.startsWith("LE_"))
					 * { DBObject query = getCompExtensionQueryObject( type, new
					 * String[] { "extension.extension.any." + type,
					 * "extension.extension.otherAttributes." + type },
					 * paramValues, "LE"); if (query != null)
					 * queryList.add(query); } } if
					 * (eventType.equals("QuantityEvent") ||
					 * eventType.equals("TransformationEvent")) { if
					 * (paramName.startsWith("GT_")) {
					 * 
					 * DBObject query = getCompExtensionQueryObject( type, new
					 * String[] { "extension.any." + type,
					 * "extension.otherAttributes." + type }, paramValues,
					 * "GT"); if (query != null) queryList.add(query); } if
					 * (paramName.startsWith("GE_")) {
					 * 
					 * DBObject query = getCompExtensionQueryObject( type, new
					 * String[] { "extension.any." + type,
					 * "extension.otherAttributes." + type }, paramValues,
					 * "GE"); if (query != null) queryList.add(query); } if
					 * (paramName.startsWith("LT_")) { DBObject query =
					 * getCompExtensionQueryObject( type, new String[] {
					 * "extension.any." + type, "extension.otherAttributes." +
					 * type }, paramValues, "LT"); if (query != null)
					 * queryList.add(query); } if (paramName.startsWith("LE_"))
					 * {
					 * 
					 * DBObject query = getCompExtensionQueryObject( type, new
					 * String[] { "extension.any." + type,
					 * "extension.otherAttributes." + type }, paramValues,
					 * "LE"); if (query != null) queryList.add(query); } }
					 */
					if (paramName.startsWith("GT_")) {
						BsonDocument query = getCompExtensionQueryObject(type,
								new String[] { "any." + type, "otherAttributes." + type }, paramValues, "GT");
						if (query != null)
							queryList.add(query);
					}
					if (paramName.startsWith("GE_")) {
						BsonDocument query = getCompExtensionQueryObject(type,
								new String[] { "any." + type, "otherAttributes." + type }, paramValues, "GE");
						if (query != null)
							queryList.add(query);
					}
					if (paramName.startsWith("LT_")) {
						BsonDocument query = getCompExtensionQueryObject(type,
								new String[] { "any." + type, "otherAttributes." + type }, paramValues, "LT");
						if (query != null)
							queryList.add(query);
					}
					if (paramName.startsWith("LE_")) {
						BsonDocument query = getCompExtensionQueryObject(type,
								new String[] { "any." + type, "otherAttributes." + type }, paramValues, "LE");
						if (query != null)
							queryList.add(query);
					}
				}
			}
		}
		return queryList;
	}

	private BsonArray makeMasterQueryObjects(String vocabularyName, boolean includeAttributes, boolean includeChildren,
			String attributeNames, String eQ_name, String wD_name, String hASATTR, Integer maxElementCount,
			Map<String, String> paramMap) {

		BsonArray queryList = new BsonArray();

		/**
		 * vocabularyName : If specified, only vocabulary elements drawn from
		 * one of the specified vocabularies will be included in the results.
		 * Each element of the specified list is the formal URI name for a
		 * vocabulary; e.g., one of the URIs specified in the table at the end
		 * of Section 7.2. If omitted, all vocabularies are considered.
		 */

		if (vocabularyName != null) {
			BsonDocument query = getINQueryObject("type", vocabularyName);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * EQ_name : If specified, the result will only include vocabulary
		 * elements whose names are equal to one of the specified values. If
		 * this parameter and WD_name are both omitted, vocabulary elements are
		 * included regardless of their names.
		 */
		Set<String> idSet = new HashSet<String>();
		if (eQ_name != null) {
			String[] eqArr = eQ_name.split(",");
			for (int i = 0; i < eqArr.length; i++) {
				String eqString = eqArr[i].trim();
				idSet.add(eqString);
			}
		}

		/**
		 * WD_name : If specified, the result will only include vocabulary
		 * elements that either match one of the specified names, or are direct
		 * or indirect descendants of a vocabulary element that matches one of
		 * the specified names. The meaning of “direct or indirect descendant”
		 * is described in Section 6.5. (WD is an abbreviation for “with
		 * descendants.”) If this parameter and EQ_name are both omitted,
		 * vocabulary elements are included regardless of their names.
		 */

		if (wD_name != null) {
			String[] eqArr = wD_name.split(",");
			for (int i = 0; i < eqArr.length; i++) {
				eqArr[i] = eqArr[i].trim();
			}
			for (int i = 0; i < eqArr.length; i++) {
				// Invoke vocabulary query with EQ_name and includeChildren
				idSet = getWDList(idSet, eqArr[i]);
			}
		}

		if (!idSet.isEmpty()) {
			BsonDocument query = getINQueryObject("id", idSet);
			if (query != null)
				queryList.add(query);
		}

		/**
		 * HASATTR : If specified, the result will only include vocabulary
		 * elements that have a non-null attribute whose name matches one of the
		 * values specified in this parameter.
		 */

		if (hASATTR != null) {
			String[] attrArr = hASATTR.split(",");
			for (int i = 0; i < attrArr.length; i++) {
				String attrString = attrArr[i].trim();
				BsonDocument query = getExistsQueryObject("attributes", attrString);
				if (query != null)
					queryList.add(query);
			}
		}

		/**
		 * EQATTR_attrnam : This is not a single parameter, but a family of
		 * parameters. If a parameter of this form is specified, the result will
		 * only include vocabulary elements that have a non-null attribute named
		 * attrname, and where the value of that attribute matches one of the
		 * values specified in this parameter.
		 */
		if (paramMap != null) {
			Iterator<String> paramIter = paramMap.keySet().iterator();
			while (paramIter.hasNext()) {
				String paramName = paramIter.next();
				String paramValues = paramMap.get(paramName);

				if (paramName.contains("EQATTR_")) {
					String type = paramName.substring(7, paramName.length());
					BsonDocument query = getVocFamilyQueryObject(type, "attributes", paramValues);
					if (query != null)
						queryList.add(query);
				}
			}
		}
		return queryList;
	}

	private long getTimeMillis(String standardDateString) {
		try {
			SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
			GregorianCalendar eventTimeCalendar = new GregorianCalendar();
			eventTimeCalendar.setTime(sdf.parse(standardDateString));
			return eventTimeCalendar.getTimeInMillis();
		} catch (ParseException e) {
			try {
				SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
				GregorianCalendar eventTimeCalendar = new GregorianCalendar();
				eventTimeCalendar.setTime(sdf.parse(standardDateString));
				return eventTimeCalendar.getTimeInMillis();
			} catch (ParseException e1) {
				Configuration.logger.log(Level.ERROR, e1.toString());
			}
		}
		// Never Happened
		return 0;
	}
}
