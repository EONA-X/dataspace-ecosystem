package org.eclipse.dse.edc.spi.telemetryagent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.edc.spi.query.Criterion;
import org.eclipse.edc.spi.query.QuerySpec;
import org.eclipse.edc.spi.query.SortOrder;
import org.eclipse.edc.spi.result.StoreResult;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static java.util.stream.IntStream.range;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStates.RECEIVED;
import static org.eclipse.dse.edc.spi.telemetryagent.TelemetryRecordStates.SENT;
import static org.eclipse.edc.spi.constants.CoreConstants.EDC_NAMESPACE;
import static org.eclipse.edc.spi.query.Criterion.criterion;
import static org.eclipse.edc.spi.result.StoreFailure.Reason.ALREADY_LEASED;
import static org.eclipse.edc.spi.result.StoreFailure.Reason.NOT_FOUND;

public abstract class TelemetryStoreTestBase {

    protected static final String CONNECTOR_NAME = "test-connector";
    protected final Clock clock = Clock.systemUTC();

    protected abstract TelemetryRecordStore getTelemetryStore();

    protected abstract boolean isLeasedBy(String participantId, String owner);

    protected abstract void leaseEntity(String recordId, String owner, Duration duration);

    protected void leaseEntity(String recordId, String owner) {
        leaseEntity(recordId, owner, Duration.ofSeconds(60));
    }

    @NotNull
    protected TelemetryRecord.Builder createRecordBuilder() {
        return TelemetryRecord.Builder.newInstance()
                .type("Type")
                .id(UUID.randomUUID().toString());
    }

    @NotNull
    protected TelemetryRecord.Builder createRecordBuilder(String id) {
        return TelemetryRecord.Builder.newInstance()
                .type("Type")
                .id(id);
    }

    protected TelemetryRecord getRecord() {
        return createRecordBuilder(UUID.randomUUID().toString()).state(SENT.code()).build();
    }

    protected TelemetryRecord getRecord(String id) {
        return createRecordBuilder(id).state(SENT.code()).build();
    }

    protected TelemetryRecord getRecord(int state) {
        return createRecordBuilder(UUID.randomUUID().toString()).state(state).build();
    }

    protected QuerySpec filter(Criterion... criteria) {
        return QuerySpec.Builder.newInstance().filter(Arrays.asList(criteria)).build();
    }

    @Nested
    class FindById {

        @Test
        void shouldFindEntityById() {
            var record = getRecord();
            getTelemetryStore().save(record);

            var actual = getTelemetryStore().findById(record.getId());

            assertThat(actual).isNotNull();
            assertThat(actual).usingRecursiveComparison().isEqualTo(record);
        }

        @Test
        @DisplayName("Verify that an entity is found by ID even when leased")
        void findById_whenLeased_shouldReturnEntity() {
            var record1 = getRecord();
            getTelemetryStore().save(record1);

            leaseEntity(record1.getId(), CONNECTOR_NAME);
            assertThat(getTelemetryStore().findById(record1.getId()))
                    .usingRecursiveComparison()
                    .isEqualTo(record1);

            var record2 = getRecord();
            getTelemetryStore().save(record2);

            leaseEntity(record2.getId(), "someone-else");
            assertThat(getTelemetryStore().findById(record2.getId()))
                    .usingRecursiveComparison()
                    .isEqualTo(record2);
        }

        @Test
        @DisplayName("Verify that null is returned when entity not found")
        void findById_notExist() {
            assertThat(getTelemetryStore().findById("not-exist")).isNull();
        }
    }

    @Nested
    class Save {

        @Test
        void save_success() {
            var record = getRecord();

            getTelemetryStore().save(record);

            assertThat(getTelemetryStore().findById(record.getId()))
                    .usingRecursiveComparison()
                    .isEqualTo(record);
        }
    }

    @Nested
    class Update {

        @Test
        @DisplayName("Verify that an existing entity is updated instead")
        void save_exists_shouldUpdate() {
            var record = getRecord();
            getTelemetryStore().save(record);

            var newRecord = createRecordBuilder(record.getId()).state(17)
                    .build();

            getTelemetryStore().save(newRecord);

            var actual = getTelemetryStore().findById(record.getId());
            assertThat(actual).isNotNull();
            assertThat(actual.getStateCount()).isEqualTo(newRecord.getStateCount());
        }

        @Test
        @DisplayName("Verify that updating an entity breaks the lease (if lease by self)")
        void leasedBySelf_shouldBreakLease() {
            var record = getRecord();
            getTelemetryStore().save(record);

            leaseEntity(record.getId(), CONNECTOR_NAME);

            var newRecord = getRecord(record.getId());

            // update should break lease
            getTelemetryStore().save(newRecord);

            assertThat(isLeasedBy(record.getId(), CONNECTOR_NAME)).isFalse();

            var next = getTelemetryStore().nextNotLeased(10);
            assertThat(next).usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt", "createdAt").containsOnly(newRecord);

        }

        @Test
        @DisplayName("Verify that updating an entity throws an exception if leased by someone else")
        void leasedByOther_shouldThrowException() {
            var record = getRecord();
            getTelemetryStore().save(record);

            leaseEntity(record.getId(), "someone-else");

            var newRecord = getRecord(record.getId());

            assertThat(isLeasedBy(record.getId(), "someone-else")).isTrue();
            var result = getTelemetryStore().save(newRecord);
            assertThat(result.failed()).isTrue();
            assertThat(result.reason()).isEqualTo(ALREADY_LEASED);
        }
    }

    @Nested
    class QueryEntityRecords {

        @Test
        void queryByState() {
            var record1 = getRecord(SENT.code());
            var record2 = getRecord(RECEIVED.code());

            getTelemetryStore().save(record1);
            getTelemetryStore().save(record2);

            var query = QuerySpec.Builder.newInstance()
                    .filter(List.of(new Criterion("state", "=", SENT.code())))
                    .build();

            Stream<TelemetryRecord> result = getTelemetryStore().queryTelemetryRecords(query);
            assertThat(result).usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt").containsOnly(record1);
        }
    }

    @Nested
    class QueryPropertiesRecords {

        @Test
        void queryByState() {
            var record1 = getRecord(SENT.code());
            var record2 = getRecord(RECEIVED.code());

            getTelemetryStore().save(record1);
            getTelemetryStore().save(record2);

            var query = QuerySpec.Builder.newInstance()
                    .filter(List.of(new Criterion("state", "=", SENT.code())))
                    .build();
            Stream<TelemetryRecord> result = getTelemetryStore().queryTelemetryRecords(query);
            assertThat(result).usingRecursiveFieldByFieldElementComparatorIgnoringFields("updatedAt").containsOnly(record1);
        }

        @Test
        void shouldReturnAllTheRecords_whenQuerySpecIsEmpty() {
            range(0, 5).mapToObj(i -> createRecordBuilder("id" + i).property("key", "test-record").build()).peek(a -> getTelemetryStore().save(a)).toList();
            var result = getTelemetryStore().queryTelemetryRecords(QuerySpec.none());
            assertThat(result).hasSize(5);
        }

        @Test
        @DisplayName("Query records with query spec")
        void limit() {
            range(1, 10).mapToObj(it -> getRecord("id" + it)).forEach(record -> getTelemetryStore().save(record));
            var querySpec = QuerySpec.Builder.newInstance().limit(3).offset(2).build();
            var recordsFound = getTelemetryStore().queryTelemetryRecords(querySpec);

            assertThat(recordsFound).isNotNull().hasSize(3);
        }

        @Test
        @DisplayName("Query records with query spec and short record count")
        void shortCount() {
            range(1, 5).mapToObj(it -> getRecord("id" + it)).forEach(record -> getTelemetryStore().save(record));
            var querySpec = QuerySpec.Builder.newInstance().limit(3).offset(2).build();
            var recordsFound = getTelemetryStore().queryTelemetryRecords(querySpec);

            assertThat(recordsFound).isNotNull().hasSize(2);
        }

        @Test
        void shouldReturnNoRecords_whenOffsetIsOutOfBounds() {
            range(1, 5).mapToObj(it -> getRecord("id" + it)).forEach(record -> getTelemetryStore().save(record));
            var querySpec = QuerySpec.Builder.newInstance().limit(3).offset(5).build();

            var recordsFound = getTelemetryStore().queryTelemetryRecords(querySpec);

            assertThat(recordsFound).isEmpty();
        }

        @Test
        void shouldThrowException_whenUnsupportedOperator() {
            var record = getRecord("id1");
            getTelemetryStore().save(record);
            var unsupportedOperator = new Criterion("propertyId", "unsupported", "42");

            assertThatThrownBy(() -> getTelemetryStore().queryTelemetryRecords(filter(unsupportedOperator))).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void shouldReturnEmpty_whenLeftOperandDoesNotExist() {
            var record = getRecord("id1");
            getTelemetryStore().save(record);
            var notExistingProperty = new Criterion("noexist", "=", "42");

            var result = getTelemetryStore().queryTelemetryRecords(filter(notExistingProperty));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Query records with query spec where the value (=rightOperand) does not exist")
        void nonExistValue() {
            var record = getRecord("id1");
            record.getProperties().put("someprop", "someval");
            getTelemetryStore().save(record);
            var notExistingValue = new Criterion("someprop", "=", "some-other-val");

            var records = getTelemetryStore().queryTelemetryRecords(filter(notExistingValue));

            assertThat(records).isEmpty();
        }

        @Test
        @DisplayName("Verifies an record query, that contains a filter expression")
        void withFilterExpression() {
            var expected = createRecordBuilder("id1").state(100).property("version", "2.0").property("contentType", "whatever").build();
            var differentVersion = createRecordBuilder("id2").state(100).property("version", "2.1").property("contentType", "whatever").build();
            var differentContentType = createRecordBuilder("id3").state(100).property("version", "2.0").property("contentType", "different").build();
            getTelemetryStore().save(expected);
            getTelemetryStore().save(differentVersion);
            getTelemetryStore().save(differentContentType);
            var filter = filter(new Criterion("version", "=", "2.0"), new Criterion("contentType", "=", "whatever"));

            var records = getTelemetryStore().queryTelemetryRecords(filter);

            assertThat(records).hasSize(1).usingRecursiveFieldByFieldElementComparator().containsOnly(expected);
        }

        @Test
        void shouldFilterByNestedProperty() {
            var nested = EDC_NAMESPACE + "nested";
            var version = EDC_NAMESPACE + "version";
            var expected = createRecordBuilder("id1").property(nested, Map.of(version, "2.0")).build();
            var differentVersion = createRecordBuilder("id2").property(nested, Map.of(version, "2.1")).build();
            getTelemetryStore().save(expected);
            getTelemetryStore().save(differentVersion);

            var records = getTelemetryStore().queryTelemetryRecords(filter(criterion("'%s'.'%s'".formatted(nested, version), "=", "2.0")));

            assertThat(records).hasSize(1).usingRecursiveFieldByFieldElementComparator().containsOnly(expected);
        }

        @Test
        void multipleFound() {
            var testRecord1 = createRecordBuilder().property("Contract-Id", "foobar").build();
            var testRecord2 = createRecordBuilder().property("Contract-Id", "barbaz").build();
            var testRecord3 = createRecordBuilder().property("Contract-Id", "barbaz").build();
            getTelemetryStore().save(testRecord1);
            getTelemetryStore().save(testRecord2);
            getTelemetryStore().save(testRecord3);
            var criterion = new Criterion("Contract-Id", "=", "barbaz");

            var records = getTelemetryStore().queryTelemetryRecords(filter(criterion));

            assertThat(records).hasSize(2).map(TelemetryRecord::getId).containsExactlyInAnyOrder(testRecord2.getId(), testRecord3.getId());
        }

        @Test
        @DisplayName("Query records using the IN operator")
        void in() {
            getTelemetryStore().save(createRecordBuilder().property("property", "id1").build());
            getTelemetryStore().save(createRecordBuilder().property("property", "id2").build());
            var criterion = new Criterion("property", "in", List.of("id1", "id2"));

            var recordsFound = getTelemetryStore().queryTelemetryRecords(filter(criterion));
            assertThat(recordsFound).isNotNull().hasSize(2);
        }

        @Test
        @DisplayName("Query records using the IN operator, invalid right operand")
        void shouldThrowException_whenOperatorInAndInvalidRightOperand() {
            getTelemetryStore().save(createRecordBuilder().property("propertyId", "id1").build());
            getTelemetryStore().save(createRecordBuilder().property("propertyId", "id2").build());
            var invalidRightOperand = new Criterion("propertyId", "in", "(id1, id2)");

            assertThatThrownBy(() -> getTelemetryStore().queryTelemetryRecords(filter(invalidRightOperand)).toList()).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void withSorting() {
            var records = range(9, 12).mapToObj(i -> createRecordBuilder("id" + i).property("key", "test-record").build()).peek(a -> getTelemetryStore().save(a)).toList();
            var spec = QuerySpec.Builder.newInstance().sortField("id").sortOrder(SortOrder.ASC).build();
            var result = getTelemetryStore().queryTelemetryRecords(spec);

            assertThat(result).usingRecursiveFieldByFieldElementComparator().containsAll(records);
        }

        @Test
        void shouldFilter_whenLikeOperator() {
            getTelemetryStore().save(createRecordBuilder().property("property", "id1").build());
            getTelemetryStore().save(createRecordBuilder().property("property", "id2").build());
            var criterion = new Criterion("property", "LIKE", "id%");

            var recordsFound = getTelemetryStore().queryTelemetryRecords(filter(criterion));
            assertThat(recordsFound).isNotNull().hasSize(2);
        }

        @Test
        void shouldFilter_whenIlikeOperator() {
            getTelemetryStore().save(createRecordBuilder().property("property", "id1").build());
            getTelemetryStore().save(createRecordBuilder().property("property", "id2").build());
            var criterion = new Criterion("property", "ilike", "id%");

            var recordsFound = getTelemetryStore().queryTelemetryRecords(filter(criterion));
            assertThat(recordsFound).isNotNull().hasSize(2);
        }

        @Test
        @DisplayName("Query records using the LIKE operator on a json value")
        void likeJson() throws JsonProcessingException {
            var record = getRecord("id1");
            var nested = Map.of("text", "test123", "number", 42, "bool", false);
            record.getProperties().put("myjson", new ObjectMapper().writeValueAsString(nested));
            getTelemetryStore().save(record);
            var criterion = new Criterion("myjson", "LIKE", "%test123%");

            var recordsFound = getTelemetryStore().queryTelemetryRecords(filter(criterion));

            assertThat(recordsFound).usingRecursiveFieldByFieldElementComparator().containsExactly(record);
        }

        @Test
        @DisplayName("Query records using two criteria, each with the LIKE operator on a nested json value")
        void likeJson_withComplexObject() throws JsonProcessingException {
            var record = getRecord("id1");
            var jsonObject = Map.of("root", Map.of("key1", "value1", "nested1", Map.of("key2", "value2", "key3", Map.of("theKey", "theValue, this is what we're looking for"))));
            record.getProperties().put("myProp", new ObjectMapper().writeValueAsString(jsonObject));
            getTelemetryStore().save(record);
            var criterion1 = new Criterion("myProp", "LIKE", "%is%what%");
            var criterion2 = new Criterion("myProp", "LIKE", "%we're%looking%");

            var recordsFound = getTelemetryStore().queryTelemetryRecords(filter(criterion1, criterion2));

            assertThat(recordsFound).usingRecursiveFieldByFieldElementComparator().containsExactly(record);
        }
    }

    @Nested
    class DeleteById {

        @Test
        @DisplayName("Delete an record that doesn't exist")
        void doesNotExist() {
            var recordDeleted = getTelemetryStore().deleteById("id1");

            assertThat(recordDeleted).isNotNull().extracting(StoreResult::reason).isEqualTo(NOT_FOUND);
        }

        @Test
        @DisplayName("Delete an record that exists")
        void exists() {
            var record = getRecord("id1");
            getTelemetryStore().save(record);

            var recordDeleted = getTelemetryStore().deleteById("id1");

            assertThat(recordDeleted).isNotNull().extracting(StoreResult::succeeded).isEqualTo(true);
            assertThat(recordDeleted.getContent()).usingRecursiveComparison().isEqualTo(record);

            assertThat(getTelemetryStore().queryTelemetryRecords(QuerySpec.none())).isEmpty();
        }
    }
}
