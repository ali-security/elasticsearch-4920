---
navigation_title: "Query vector builders"
applies_to:
  stack: all
  serverless: all
---

# Query vector builders [query-vector-builders]

A query vector builder generates a query vector at search time from an input, rather than requiring you to pre-compute and supply the vector yourself. This simplifies writing `knn` queries that use inference models to encode inputs.

Query vector builders are used with the `query_vector_builder` parameter in [`knn` queries](/reference/query-languages/query-dsl/query-dsl-knn-query.md) and [`knn` retrievers](/reference/elasticsearch/rest-apis/retrievers/knn-retriever.md).

Three query vector builders are available:

- [`text_embedding`](#text-embedding-query-vector-builder) — generate a vector from a text string using a machine learning model
- [`embedding`](#embedding-query-vector-builder) — generate a vector from multimodal inputs (text or base64-encoded images) using an inference service
- [`lookup`](#lookup-query-vector-builder) — use a vector stored in an existing document

## `text_embedding` [text-embedding-query-vector-builder]

The `text_embedding` query vector builder generates a query vector by running a text string through a machine learning model. Use this when you have a [`dense_vector`](/reference/elasticsearch/mapping-reference/dense-vector.md) field indexed with a model, and want to search using natural language text at query time.

If all queried fields are of type [`semantic_text`](/reference/elasticsearch/mapping-reference/semantic-text.md), the `model_id` may be inferred from the field mapping.

### Parameters [text-embedding-parameters]

`model_id`
:   (Optional, string) The ID of the machine learning model to use for text embedding. Required unless querying a `semantic_text` field.

`model_text`
:   (Required, string) The text to generate an embedding for.

### Example [text-embedding-example]

```js
{
  "query": {
    "knn": {
      "field": "my-vector-field",
      "k": 10,
      "num_candidates": 100,
      "query_vector_builder": {
        "text_embedding": {
          "model_id": "my-text-embedding-model",
          "model_text": "Search for documents about vector search"
        }
      }
    }
  }
}
```
% NOTCONSOLE

## `embedding` [embedding-query-vector-builder]

```{applies_to}
stack: preview 9.4
serverless: preview
```

The `embedding` query vector builder generates a query vector from multimodal inputs — including text and base64-encoded images — using an inference service that uses the `EMBEDDING` task type. Use this when you need to create query vectors from non-text inputs or mixed inputs.

### Parameters [embedding-parameters]

`inference_id`
:   (Required, string) The ID of the inference endpoint to use. Must refer to an inference service that uses the `EMBEDDING` task type.

`input`
:   (Required, object, array of objects, or string) The input to generate an embedding for. Can be one of:

    - A **string** — a shorthand for a single text input.
    - An **object** with the following fields:

        `type`
        :   (Required, string) The type of the input. One of:
            - `text` — the input is a raw text string.
            - `image` — the input is a base64-encoded image.

        `format`
        :   (Optional, string) The format of the input. If not specified, the default format for the given `type` is used. Currently supported formats:
            - `text` — for `type: text`
            - `base64` — for `type: image`

        `value`
        :   (Required, string) The input value. For `type: image`, must be a base64-encoded data URI with the format `data:{MIME-type};base64,...`.

    - An **array of objects**, each with the same fields as above. At least one object must be provided.

`timeout`
:   (Optional, time value) The timeout for the inference request. Defaults to `30s`.

### Examples [embedding-examples]

**Text input (shorthand)**:

```js
{
  "query": {
    "knn": {
      "field": "my-vector-field",
      "query_vector_builder": {
        "embedding": {
          "inference_id": "my-multimodal-endpoint",
          "input": "Search for documents about vector search"
        }
      }
    }
  }
}
```
% NOTCONSOLE

**Text input (explicit)**:

```js
{
  "query": {
    "knn": {
      "field": "my-vector-field",
      "query_vector_builder": {
        "embedding": {
          "inference_id": "my-multimodal-endpoint",
          "input": {
            "type": "text",
            "value": "Search for documents about vector search"
          }
        }
      }
    }
  }
}
```
% NOTCONSOLE

**Image input (base64-encoded)**:

```js
{
  "query": {
    "knn": {
      "field": "my-vector-field",
      "query_vector_builder": {
        "embedding": {
          "inference_id": "my-multimodal-endpoint",
          "input": {
            "type": "image",
            "value": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
          }
        }
      }
    }
  }
}
```
% NOTCONSOLE

**Multiple inputs**:

```js
{
  "query": {
    "knn": {
      "field": "my-vector-field",
      "query_vector_builder": {
        "embedding": {
          "inference_id": "my-multimodal-endpoint",
          "input": [
            {
              "type": "text",
              "value": "Search for documents about vector search"
            },
            {
              "type": "image",
              "value": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
            }
          ]
        }
      }
    }
  }
}
```
% NOTCONSOLE

## `lookup` [lookup-query-vector-builder]

```{applies_to}
stack: ga 9.4+
```

The `lookup` query vector builder fetches a query vector from an existing document in an index. Use this when you have already indexed vectors in a document and want to use one of them as the query vector for a `knn` search.

### Parameters [lookup-parameters]

`id`
:   (Required, string) The ID of the document to look up.

`index`
:   (Required, string) The name of the index containing the document to look up.

`path`
:   (Required, string) The name of the vector field in the document to use as the query vector.

`routing`
:   (Optional, string) The routing value to use when looking up the document.

### Example [lookup-example]

The following example fetches the document with ID `vector_doc_0` from the `some_vector_index` index and uses the value of the `vector_field` field as the query vector.

```js
{
  "query": {
    "knn": {
      "field": "vector_field",
      "query_vector_builder": {
        "lookup": {
          "id": "vector_doc_0",
          "index": "some_vector_index",
          "path": "vector_field"
        }
      }
    }
  }
}
```
% NOTCONSOLE
