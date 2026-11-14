# Questions: group-create-inline

## Q1 spec -> user
When is the new group saved? The spec saves group and membership at the moment of *Create*
(AC-5), so cancelling "Select groups" afterwards keeps them. The alternative is to save only
when "Select groups" is confirmed with OK, so Cancel discards the new group too.

## Q2 spec -> user
Should a name that already exists be refused? The spec allows duplicates because the existing
group dialog does (AC-9).
