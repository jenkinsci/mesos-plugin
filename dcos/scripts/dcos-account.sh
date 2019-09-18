#!/bin/sh
#
# Write DC/OS credentials to file and update environment
# variables accordingly.
#

# Check if DCOS_SERVICE_ACCOUNT_CREDENTIAL is present in env variables
# and if so write content to local disk. This will overwrite the env 
# variable with the file location.
write_cred_file()
{
    if [ -n "$DCOS_SERVICE_ACCOUNT_CREDENTIAL" ] && [ "$DCOS_SERVICE_ACCOUNT_CREDENTIAL" != file* ]; then
        local target_file="$JENKINS_HOME/.dcos_acct_creds"
        printf "%s" "$DCOS_SERVICE_ACCOUNT_CREDENTIAL" > $target_file

        # fix env variable
        DCOS_SERVICE_ACCOUNT_CREDENTIAL="file://$target_file"
        export DCOS_SERVICE_ACCOUNT_CREDENTIAL
    fi
}

write_cred_file
