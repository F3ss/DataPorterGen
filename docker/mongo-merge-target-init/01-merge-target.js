const target = db.getSiblingDB("merge_catalog");

target.createCollection("customers", {
  validator: { $jsonSchema: { bsonType: "object", required: ["email", "active"], properties: {
    email: { bsonType: "string" }, active: { bsonType: "bool" }
  }}}, validationLevel: "strict", validationAction: "error"
});
target.customers.insertMany([
  {
    _id: ObjectId("65a000000000000000000001"), email: "target-wins@example.test",
    active: true, targetMarker: "retained"
  },
  {
    _id: ObjectId("65afffffffffffffffffffff"), email: "target-only@example.test",
    active: false, targetOnly: true
  }
]);
target.customers.createIndex({ email: 1 }, { unique: true, name: "customers_email_unique" });
target.customers.createIndex({ active: 1, createdAt: -1 }, { name: "customers_active_created" });
target.customers.createIndex({ targetOnly: 1 }, { name: "customers_target_only" });

target.createCollection("target_only_collection");
target.target_only_collection.insertOne({
  _id: ObjectId("65d000000000000000000001"), retained: true
});
target.createView("target_only_view", "target_only_collection", [{ $match: { retained: true } }]);
target.createView("active_customers_view", "customers", [
  { $match: { active: true } },
  { $project: { email: 1, name: 1 } }
]);

print("MERGE_TARGET_INIT_OK database=merge_catalog");
