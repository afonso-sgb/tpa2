# Sample Email Files for Testing

This directory contains sample email files for testing the distributed search system.

## File Format

Each file should be a plain text file with `.txt` extension.

## Creating Test Files

You can create test email files using the provided script or manually.

### Using Docker (Local Testing)

```bash
# Copy files to shared volume
docker cp email001.txt tpa2-worker1:/var/sharedfiles/
```

### Using GCP (Production)

```bash
# Copy to GlusterFS mount point on node1
gcloud compute scp email*.txt tpa2-node1:/tmp/
gcloud compute ssh tpa2-node1 -- 'sudo mv /tmp/email*.txt /var/sharedfiles/'
```

## Sample Content Examples

**email001.txt:**
```
From: alice@example.com
To: team@example.com
Subject: Team Meeting Tomorrow

Hi everyone,

This is a reminder about our team meeting scheduled for tomorrow at 10:00 AM.
Please review the project status before the meeting.

Best regards,
Alice
```

**email002.txt:**
```
From: bob@example.com
To: alice@example.com
Subject: Project Update

Hello Alice,

The project milestone has been completed successfully.
The deadline for the next phase is approaching next week.

Thanks,
Bob
```

**email003.txt:**
```
From: carol@example.com
To: team@example.com
Subject: Meeting Rescheduled

Dear team,

The meeting scheduled for tomorrow has been rescheduled to the afternoon at 2:00 PM.

Regards,
Carol
```

## Search Examples

Based on these sample files:

```bash
# Find emails about meetings
java -jar userapp.jar search meeting

# Find emails about both meeting and tomorrow
java -jar userapp.jar search meeting tomorrow

# Find emails about project deadline
java -jar userapp.jar search project deadline
```
