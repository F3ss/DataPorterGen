const source = db.getSiblingDB("source_catalog");
const names = source.getCollectionInfos().map(info => info.name);

if (!names.includes("customers")) {
  source.createCollection("customers", {
    validator: { $jsonSchema: { bsonType: "object", required: ["email", "active"], properties: {
      email: { bsonType: "string" }, active: { bsonType: "bool" }
    }}}, validationLevel: "strict", validationAction: "error"
  });
} else {
  source.runCommand({ collMod: "customers", validator: { $jsonSchema: { bsonType: "object",
    required: ["email", "active"], properties: { email: { bsonType: "string" }, active: { bsonType: "bool" } }
  }}, validationLevel: "strict", validationAction: "error" });
}
if (!names.includes("orders")) source.createCollection("orders");
if (!names.includes("events")) source.createCollection("events", { capped: true, size: 1048576, max: 1000 });
if (!names.includes("empty_collection")) source.createCollection("empty_collection");

source.customers.replaceOne({ _id: ObjectId("65a000000000000000000001") }, {
  _id: ObjectId("65a000000000000000000001"), email: "alice@example.test", name: "Алиса 東京",
  active: true, age: NumberInt(31), visits: NumberLong("9007199254740991"), ratio: 1.25,
  balance: NumberDecimal("1234.50"), createdAt: ISODate("2024-01-01T00:00:00Z"), nullable: null,
  binary: BinData(0, "AAECAwQFBgc="), tags: ["vip", "международный"],
  address: { city: "Ташкент", zip: NumberInt(100000) }
}, { upsert: true });
source.customers.replaceOne({ _id: ObjectId("65a000000000000000000002") }, {
  _id: ObjectId("65a000000000000000000002"), email: "bob@example.test", name: "Bob",
  active: false, age: NumberInt(42), balance: NumberDecimal("0.01"), createdAt: ISODate("2024-02-02T00:00:00Z")
}, { upsert: true });

source.orders.replaceOne({ _id: ObjectId("65b000000000000000000001") }, {
  _id: ObjectId("65b000000000000000000001"), customerId: ObjectId("65a000000000000000000001"),
  total: NumberDecimal("99.95"), lines: [{ sku: "SKU-1", quantity: NumberInt(2) }]
}, { upsert: true });

if (source.events.countDocuments({ _id: ObjectId("65c000000000000000000001") }) === 0) {
  source.events.insertOne({ _id: ObjectId("65c000000000000000000001"), kind: "created",
    at: ISODate("2030-01-01T00:00:00Z"), bsonTimestamp: Timestamp(1, 42), payload: BinData(0, "AQIDBA==") });
}

source.customers.createIndex({ email: 1 }, { unique: true, name: "customers_email_unique" });
source.customers.createIndex({ active: 1, createdAt: -1 }, { name: "customers_active_created" });
source.customers.createIndex({ name: 1 }, { name: "customers_name_partial", partialFilterExpression: { name: { $exists: true } } });
source.customers.createIndex({ accountNumber: "hashed" }, { name: "customers_account_number_hashed" });
source.customers.createIndex({ age: 1 }, { sparse: true, name: "customers_age_sparse" });
source.orders.createIndex({ customerId: 1, total: -1 }, { name: "orders_customer_total" });
source.orders.createIndex({ expiresAt: 1 }, { expireAfterSeconds: NumberLong(86400), name: "orders_expires_at_ttl" });

if (!source.getCollectionInfos({ name: "active_customers_view" }).length) {
  source.createView("active_customers_view", "customers", [{ $match: { active: true } }, { $project: { email: 1, name: 1 } }]);
}

print("SOURCE_INIT_OK database=source_catalog");
