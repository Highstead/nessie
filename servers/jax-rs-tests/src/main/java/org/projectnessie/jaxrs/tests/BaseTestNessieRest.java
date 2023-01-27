/*
 * Copyright (C) 2023 Dremio
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.projectnessie.jaxrs.tests;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.projectnessie.model.Validation.REF_NAME_MESSAGE;

import io.restassured.RestAssured;
import io.restassured.builder.RequestSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.data.MapEntry;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.projectnessie.client.ext.NessieApiVersion;
import org.projectnessie.client.ext.NessieApiVersions;
import org.projectnessie.client.ext.NessieClientUri;
import org.projectnessie.error.NessieError;
import org.projectnessie.model.Branch;
import org.projectnessie.model.CommitMeta;
import org.projectnessie.model.CommitResponse;
import org.projectnessie.model.Content;
import org.projectnessie.model.ContentKey;
import org.projectnessie.model.ContentResponse;
import org.projectnessie.model.DiffResponse;
import org.projectnessie.model.EntriesResponse;
import org.projectnessie.model.GetMultipleContentsResponse;
import org.projectnessie.model.IcebergTable;
import org.projectnessie.model.ImmutableBranch;
import org.projectnessie.model.ImmutableOperations;
import org.projectnessie.model.Namespace;
import org.projectnessie.model.Operation;
import org.projectnessie.model.Operation.Put;
import org.projectnessie.model.Operations;
import org.projectnessie.model.Reference;
import org.projectnessie.model.SingleReferenceResponse;

/** REST specific tests. */
public abstract class BaseTestNessieRest extends BaseTestNessieApi {

  protected URI clientUri;

  @BeforeEach
  public void enableLogging() {
    RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
  }

  @SuppressWarnings("JUnitMalformedDeclaration")
  @BeforeEach
  void setupRestUri(@NessieClientUri URI uri) {
    clientUri = uri;
    RestAssured.baseURI = uri.toString();
    RestAssured.port = uri.getPort();
  }

  @BeforeAll
  static void setupRestAssured() {
    RestAssured.requestSpecification =
        new RequestSpecBuilder()
            .setContentType(ContentType.JSON)
            .setAccept(ContentType.JSON)
            .build();
  }

  protected static RequestSpecification rest() {
    return given().when().baseUri(RestAssured.baseURI).basePath("").contentType(ContentType.JSON);
  }

  private static Content withoutId(Content content) {
    if (content instanceof IcebergTable) {
      return IcebergTable.builder().from(content).id(null).build();
    }
    throw new IllegalArgumentException("Expected IcebergTable, got " + content);
  }

  private Branch commitV1(ContentKey contentKey, Content content, Branch branch) {
    Operations contents =
        ImmutableOperations.builder()
            .addOperations(Put.of(contentKey, content))
            .commitMeta(CommitMeta.builder().author("test author").message("").build())
            .build();
    return rest()
        .body(contents)
        .queryParam("expectedHash", branch.getHash())
        .post("trees/branch/{branch}/commit", branch.getName())
        .then()
        .statusCode(200)
        .extract()
        .as(Branch.class);
  }

  private Branch createBranchV1(String name) {
    Branch test = ImmutableBranch.builder().name(name).build();
    return rest().body(test).post("trees/tree").then().statusCode(200).extract().as(Branch.class);
  }

  @NessieApiVersions(versions = {NessieApiVersion.V1})
  @ParameterizedTest
  @CsvSource({
    "simple,name",
    "simple,dotted.txt",
    "dotted.prefix,name",
    "dotted.prefix,dotted.txt",
  })
  public void testGetContent(String ns, String name) {
    Branch branch = createBranchV1("content-test-" + UUID.randomUUID());
    IcebergTable table = IcebergTable.of("content-table1", 42, 42, 42, 42);

    ContentKey key = ContentKey.of(ns, name);
    branch = commitV1(key, table, branch);

    Content content =
        rest()
            .queryParam("ref", branch.getName())
            .queryParam("hashOnRef", branch.getHash())
            .get(String.format("contents/%s", key.toPathString()))
            .then()
            .statusCode(200)
            .extract()
            .as(Content.class);

    assertThat(withoutId(content)).isEqualTo(table);
  }

  @Test
  @NessieApiVersions(versions = {NessieApiVersion.V1})
  public void testGetUnknownContentType() {
    String nsName = "foo";
    String branchName = "unknown-content-type";
    String path = String.format("namespaces/namespace/%s/%s", branchName, nsName);

    createBranchV1(branchName);
    Namespace ns = Namespace.of("id");

    // Elicit 415 when PUTting a namespace with a content type not consumed by the server
    rest()
        .body(ns)
        .contentType(ContentType.TEXT)
        .put(path)
        .then()
        .statusCode(415)
        .statusLine(StringContains.containsStringIgnoringCase("Unsupported Media Type"));

    // Rerun the request, but with a supported content type (text/json)
    rest().body(ns).put(path).then().statusCode(200);
  }

  @Test
  @NessieApiVersions(versions = {NessieApiVersion.V1})
  public void testNullMergePayload() {
    NessieError response =
        rest()
            .post("trees/branch/main/merge")
            .then()
            .statusCode(400)
            .extract()
            .as(NessieError.class);
    assertThat(response.getMessage()).contains(".merge: must not be null");
  }

  @Test
  @NessieApiVersions(versions = {NessieApiVersion.V1})
  public void testNullTagPayload() {
    NessieError response =
        rest().put("trees/tag/newTag").then().statusCode(400).extract().as(NessieError.class);
    assertThat(response.getMessage()).contains(".assignTo: must not be null");
    assertThat(response.getMessage()).contains(".expectedHash: must not be null");
  }

  @NessieApiVersions(versions = {NessieApiVersion.V1})
  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "abc'",
        ".foo",
        "abc'def'..'blah",
        "abc'de..blah",
        "abc'de@{blah",
      })
  public void testInvalidTag(String invalidTagName) {
    String validHash = "0011223344556677";
    String validRefName = "hello";

    assertAll(
        () ->
            assertThat(
                    rest()
                        // Need the string-ified JSON representation of `Tag` here, because `Tag`
                        // itself performs validation.
                        .body(
                            "{\"type\": \"TAG\", \"name\": \""
                                + invalidTagName
                                + "\", \"hash\": \""
                                + validHash
                                + "\"}")
                        .put("trees/tag/" + validRefName)
                        .then()
                        .statusCode(400)
                        .extract()
                        .as(NessieError.class)
                        .getMessage())
                .contains(
                    "Cannot construct instance of `org.projectnessie.model.ImmutableTag`, problem: "
                        + REF_NAME_MESSAGE
                        + " - but was: "
                        + invalidTagName
                        + "\n"),
        () ->
            assertThat(
                    rest()
                        .body(
                            "{\"type\": \"TAG\", \"name\": \""
                                + invalidTagName
                                + "\", \"hash\": \""
                                + validHash
                                + "\"}")
                        .put("trees/tag/" + validRefName)
                        .then()
                        .statusCode(400)
                        .extract()
                        .as(NessieError.class)
                        .getMessage())
                .contains(
                    "Cannot construct instance of `org.projectnessie.model.ImmutableTag`, problem: "
                        + REF_NAME_MESSAGE),
        () ->
            assertThat(
                    rest()
                        .body(
                            "{\"type\": \"FOOBAR\", \"name\": \""
                                + validRefName
                                + "\", \"hash\": \""
                                + validHash
                                + "\"}")
                        .put("trees/tag/" + validRefName)
                        .then()
                        .statusCode(400)
                        .extract()
                        .as(NessieError.class)
                        .getMessage())
                .contains(
                    "Could not resolve type id 'FOOBAR' as a subtype of `org.projectnessie.model.Reference`"));
  }

  private Branch createBranchV2(String branchName) {
    // Note: no request body means creating the new branch from the HEAD of the default branch.
    return (Branch)
        rest()
            .queryParam("name", branchName)
            .queryParam("type", Reference.ReferenceType.BRANCH.name())
            .post("trees")
            .then()
            .statusCode(200)
            .extract()
            .as(SingleReferenceResponse.class)
            .getReference();
  }

  private Branch commitV2(Branch branch, ContentKey key, IcebergTable table) {
    return rest()
        .body(
            ImmutableOperations.builder()
                .commitMeta(CommitMeta.fromMessage("test commit"))
                .addOperations(Operation.Put.of(key, table))
                .build())
        .post("trees/{ref}/history/commit", branch.toPathString())
        .then()
        .statusCode(200)
        .extract()
        .as(CommitResponse.class)
        .getTargetBranch();
  }

  private Content getContentV2(Reference reference, ContentKey key) {
    return rest()
        .get("trees/{ref}/contents/{key}", reference.toPathString(), key.toPathString())
        .then()
        .statusCode(200)
        .extract()
        .as(ContentResponse.class)
        .getContent();
  }

  @NessieApiVersions(versions = {NessieApiVersion.V2})
  @ParameterizedTest
  @CsvSource({
    "simple1,testKey",
    "simple2,test.Key",
    "simple3,test\u001DKey",
    "simple4,test\u001Dnested.Key",
    "with/slash1,testKey",
    "with/slash2,test.Key",
    "with/slash3,test\u001DKey",
    "with/slash4,test\u001D.nested.Key",
  })
  void testGetSingleContent(String branchName, String encodedKey) {
    Branch branch = createBranchV2(branchName);
    ContentKey key = ContentKey.fromPathString(encodedKey);
    IcebergTable table = IcebergTable.of("test-location", 1, 2, 3, 4);
    branch = commitV2(branch, key, table);
    assertThat(getContentV2(branch, key))
        .asInstanceOf(InstanceOfAssertFactories.type(IcebergTable.class))
        .extracting(IcebergTable::getMetadataLocation)
        .isEqualTo("test-location");
  }

  @NessieApiVersions(versions = {NessieApiVersion.V2})
  @ParameterizedTest
  @ValueSource(strings = {"simple", "with/slash"})
  void testGetSeveralContents(String branchName) {
    Branch branch = createBranchV2(branchName);
    ContentKey key1 = ContentKey.of("test", "Key");
    ContentKey key2 = ContentKey.of("test.with.dot", "Key");
    IcebergTable table1 = IcebergTable.of("loc1", 1, 2, 3, 4);
    IcebergTable table2 = IcebergTable.of("loc2", 1, 2, 3, 4);
    branch = commitV2(branch, key1, table1);
    branch = commitV2(branch, key2, table2);

    EntriesResponse entries =
        rest()
            .get("trees/{ref}/entries", branch.toPathString())
            .then()
            .statusCode(200)
            .extract()
            .as(EntriesResponse.class);
    assertThat(entries.getEntries())
        .hasSize(2)
        .allSatisfy(e -> assertThat(e.getContentId()).isNotNull());

    Stream<MapEntry<ContentKey, String>> contents =
        rest()
            .queryParam("key", key1.toPathString(), key2.toPathString())
            .get("trees/{ref}/contents", branch.toPathString())
            .then()
            .statusCode(200)
            .extract()
            .as(GetMultipleContentsResponse.class)
            .getContents()
            .stream()
            .map(
                content ->
                    entry(
                        content.getKey(),
                        ((IcebergTable) content.getContent()).getMetadataLocation()));

    assertThat(contents).containsExactlyInAnyOrder(entry(key1, "loc1"), entry(key2, "loc2"));
  }

  /** Dedicated test for human-readable references in URL paths. */
  @SuppressWarnings("JUnitMalformedDeclaration")
  @NessieApiVersions(versions = {NessieApiVersion.V2})
  @Test
  void testBranchWithSlashInUrlPath(@NessieClientUri URI clientUri) throws IOException {
    Branch branch = createBranchV2("test/branch/name1");

    assertThat(
            rest()
                .get("trees/test/branch/name1@")
                .then()
                .statusCode(200)
                .extract()
                .as(SingleReferenceResponse.class)
                .getReference())
        .isEqualTo(branch);

    // The above request encodes `@` as `%40`, now try the same URL with a literal `@` char to
    // simulate handwritten `curl` requests.
    URL url = new URL(clientUri + "/trees/test/branch/name1@");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    assertThat(conn.getResponseCode()).isEqualTo(200);
    conn.disconnect();
  }

  /** Dedicated test for human-readable default branch references in URL paths. */
  @NessieApiVersions(versions = {NessieApiVersion.V2})
  @Test
  void testDefaultBranchSpecInUrlPath() {
    Reference main =
        rest()
            .get("trees/-")
            .then()
            .statusCode(200)
            .extract()
            .as(SingleReferenceResponse.class)
            .getReference();
    assertThat(main.getName()).isEqualTo("main");

    Branch branch = createBranchV2("testDefaultBranchSpecInUrlPath");
    ContentKey key = ContentKey.of("test1");
    commitV2(branch, key, IcebergTable.of("loc", 1, 2, 3, 4));

    assertThat(
            rest()
                .get("trees/-/diff/{name}", branch.getName())
                .then()
                .statusCode(200)
                .extract()
                .as(DiffResponse.class)
                .getDiffs())
        .satisfiesExactly(e -> assertThat(e.getKey()).isEqualTo(key));

    assertThat(
            rest()
                .get("trees/{name}/diff/-", branch.getName())
                .then()
                .statusCode(200)
                .extract()
                .as(DiffResponse.class)
                .getDiffs())
        .satisfiesExactly(e -> assertThat(e.getKey()).isEqualTo(key));

    assertThat(
            rest()
                .get("trees/-/diff/-")
                .then()
                .statusCode(200)
                .extract()
                .as(DiffResponse.class)
                .getDiffs())
        .isEmpty();
  }

  @NessieApiVersions(versions = {NessieApiVersion.V2})
  @Test
  public void testCommitMetaAttributes() {
    Branch branch = createBranchV2("testCommitMetaAttributes");
    commitV2(branch, ContentKey.of("test-key"), IcebergTable.of("meta", 1, 2, 3, 4));

    String response =
        rest()
            .get("trees/{ref}/history", branch.getName())
            .then()
            .statusCode(200)
            .extract()
            .asString();
    assertThat(response)
        .doesNotContain("\"author\"") // only for API v1
        .contains("\"authors\"")
        .doesNotContain("\"signedOffBy\"") // only for API v1
        .contains("allSignedOffBy");
  }
}